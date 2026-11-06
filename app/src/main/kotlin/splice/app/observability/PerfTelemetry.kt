package splice.app.observability

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import splice.app.sources.PerfSourceFiles
import splice.app.sources.observability.PerfAggregateStore
import splice.app.sources.observability.PerfEvidenceFileSource
import splice.core.config.ConfigService
import splice.core.perf.observation.NoPerfObserver
import splice.core.perf.observation.PerfAccumulator
import splice.core.perf.observation.PerfAggregateSource
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfObserver
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.MonoClock
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.head.perf.RequestEvidenceFiles
import splice.observability.export.PerfExports
import splice.observability.profile.ProfileManager
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker
import splice.upstream.retry.InflightGate
import splice.usage.alerts.AlertDelivery
import splice.usage.alerts.AlertStore
import splice.usage.alerts.PerfWebhookDelivery
import splice.usage.perf.RecordedAccounts
import splice.usage.perf.RequestProjects
import splice.usage.perf.history.PerfEvidenceSinkSource
import splice.usage.perf.objectives.PerfAlertEngine
import splice.usage.perf.objectives.PerfObjectiveStore
import splice.usage.perf.query.PerfQueryService
import java.util.concurrent.ConcurrentHashMap

/** The daemon's single composition owner. Construction happens before head producers; no profile starts here. */
internal class PerfTelemetry(
    private val config: ConfigService,
    private val heads: Set<String>,
    rows: PerfSourceFiles,
    alerts: AlertStore,
    private val log: LogSink,
) {
    private val io = ProcessDispatchers().io()
    private val ticker = ProcessTicker()
    private val paths = config.statePaths
    private val clock = WallClock(System::currentTimeMillis)
    private val bootConfig = config.getConfig()
    val enabled = bootConfig.perfCollectionEnabled
    val identity = PerfBuildIdentity.create(PerfConfigRevision.of(bootConfig.asMap()) ?: "unknown:unsupported_policy")
    private val accumulator = PerfAccumulator(identity)
    private val aggregate = PerfAggregateStore(paths.perfAggregatesDir, identity, clock)
    private val evidenceFiles = RequestEvidenceFiles(paths.perfEvidenceDir, rows.kept, clock)
    private val evidenceStores = heads.associateWith(evidenceFiles::forHead)
    val exports = PerfExports(
        bootConfig.perfExportConfig(),
        identity,
        PerfAggregateSource {
            accumulator.snapshot().let { snapshot ->
                if (enabled) {
                    snapshot
                } else {
                    snapshot.copy(
                        coverage = snapshot.coverage.copy(
                            observedMs = 0,
                            issues = snapshot.coverage.issues + PerfIssue("collection_disabled", "collection"),
                        ),
                    )
                }
            }
        },
        clock,
    )
    private val observer = PerfFanout(
        listOf(
            accumulator,
            exports,
            PerfObserver { event ->
                evidenceStores[event.scope.head]?.record(event) ?: true
            },
        ),
    ).also { it.loss = PerfLoss { head -> evidenceFiles.faultCoverage(head.takeIf { it in heads }) } }
    val recorder =
        PerfRecorder(identity, if (enabled) observer else NoPerfObserver, ElapsedClock(MonoClock::nowMs), clock)
    val owners = PerfRuntimeOwners()
    val runtime = PerfRuntimeSampling(identity, recorder, owners, enabled)
    val configuration = PerfConfigHistory(paths.perfConfigurationFile)
    private val tracking = PerfConfigTracking(config, heads, identity.bootId, configuration)
    private val evidence = PerfEvidenceFileSource(
        paths.perfEvidenceDir,
        clock,
        PerfEvidenceSinkSource {
            evidenceStores.values.map { it.sink.status() }
        },
    )
    val sources = PerfLiveSources(heads, rows, aggregate, accumulator, evidence, configuration).also {
        it.enabled = enabled
        it.runtimeSource = runtime
    }
    val objectives = PerfObjectiveStore(paths.perfObjectivesFile).also { sources.objectives = it }
    var projects: RequestProjects? = null
    val query = PerfQueryService(sources, RequestProjects { projects?.invoke() ?: EmptyPerfProjects })
    val profiles = ProfileManager(
        paths.perfProfilesDir,
        identity.copy(configRevision = "unknown:effective_configuration_requires_intervals"),
        PerfNativeProfiler.installed(),
    )
    private val scope = LifecycleScope(io)
    private val deliveryClient = HttpClient(Java) {
        install(HttpTimeout) {
            connectTimeoutMillis = DELIVERY_TIMEOUT_MS
            requestTimeoutMillis = DELIVERY_TIMEOUT_MS
            socketTimeoutMillis = DELIVERY_TIMEOUT_MS
        }
    }
    private val delivery = PerfWebhookDelivery(AlertDelivery(alerts, log, scope, deliveryClient), scope, objectives)
    val alertEngine = PerfAlertEngine(query, objectives, delivery)
    private val gates = ConcurrentHashMap<String, InflightGate>()
    private var sampler: kotlinx.coroutines.Job? = null
    private var accountWorker: kotlinx.coroutines.Job? = null
    private val configTicks = Channel<Unit>(Channel.CONFLATED)
    private var configWorker: kotlinx.coroutines.Job? = null

    fun gate(head: String, gate: InflightGate) { gates[head] = gate }

    fun start() {
        if (sampler != null) return
        if (enabled) aggregate.openWriter()
        if (enabled) heads.forEach { evidenceFiles.startCoverage(it, identity.bootId) }
        tracking.afterChange = PerfConfigSignal { configTicks.trySend(Unit) }
        tracking.start()
        configWorker = scope.launch {
            for (tick in configTicks) {
                runInterruptible { tracking.drain(); gates.values.forEach(InflightGate::sampleObservations) }
            }
        }
        sampler = scope.launch { samplePeriodically() }
    }

    private suspend fun samplePeriodically() {
        var due = MonoClock.nowMs()
        while (currentCoroutineContext().isActive) {
            val start = MonoClock.nowMs()
            measured("self_scheduler_delay_ms", (start - due).coerceAtLeast(0), "perf_sampler")
            Cancellables.runCatchingBestEffort { runInterruptible { tick() } }.onFailure {
                recorder.emit(
                    PerfScope(work = "instrumentation"),
                    PerfEventKind.SELF_COST,
                    mapOf("self_failures" to 1.0),
                )
                log("[perf] periodic observation or persistence failed\n")
            }
            val ended = MonoClock.nowMs()
            measured("self_sampler_ms", ended - start, "perf_sampler")
            due = if (ended - start < SAMPLE_MS) start + SAMPLE_MS else ended + SAMPLE_MS
            if (!ticker.awaitTick((due - MonoClock.nowMs()).coerceAtLeast(1L))) break
        }
    }

    fun accounts(names: RecordedAccounts) {
        accountWorker?.cancel()
        accountWorker = scope.launch {
            while (isActive) {
                heads.forEach { head ->
                    Cancellables.runCatchingCancellable { names.forHead(head) }
                        .onSuccess { sources.accountNames(head, it) }
                        .onFailure { sources.accountNames(head, null) }
                }
                if (!ticker.awaitTick(ACCOUNT_REFRESH_MS)) break
            }
        }
    }

    private fun tick() {
        tracking.drain()
        configuration.persist(clock())
        if (enabled) {
            runtime.sample()
            gates.values.forEach(InflightGate::sampleObservations)
            checkpoint()
            evidenceFiles.checkpointCoverage()
        }
        PerfWorkBudget.release(alertEngine.evaluate(clock()))
    }

    private fun checkpoint() {
        if (aggregate.status().pending > 0) return
        val lease = PerfWorkBudget.acquire(SNAPSHOT_BYTES) ?: return
        lease.use { aggregate.append(accumulator.snapshot()) }
    }

    fun measured(metric: String, durationMs: Long, owner: String = "control_query") {
        recorder.emit(
            PerfScope(work = "instrumentation", model = owner),
            PerfEventKind.SELF_COST,
            mapOf(metric to durationMs.toDouble()),
        )
    }

    /** Called after heads/MCP stop. Flush while source data is still alive, then release dependent owners. */
    suspend fun close() = withContext(io) {
        config.effectiveObserver = null
        sampler?.cancel()
        accountWorker?.cancel()
        configWorker?.cancel()
        sampler?.join()
        accountWorker?.join()
        configWorker?.join()
        tracking.drain()
        Cancellables.runCatchingCancellable { configuration.persist(clock()) }.onFailure {
            log("[perf] final configuration provenance checkpoint failed\n")
        }
        if (enabled) checkpoint()
        if (enabled) heads.forEach { evidenceFiles.stopCoverage(it) }
        AsyncFileIo.awaitDirectory(paths.perfEvidenceDir)
        evidenceFiles.close()
        aggregate.close()
        exports.flush()
        profiles.close()
        alertEngine.close()
        delivery.close()
        scope.cancel()
        deliveryClient.close()
        exports.close()
        objectives.close()
        runtime.close()
        tracking.close()
        accumulator.close()
    }
}

private const val SAMPLE_MS = 5_000L
private const val ACCOUNT_REFRESH_MS = 30_000L
private const val SNAPSHOT_BYTES = 2L * 1_024 * 1_024
private const val DELIVERY_TIMEOUT_MS = 10_000L
