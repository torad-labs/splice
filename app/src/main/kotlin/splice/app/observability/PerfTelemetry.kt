package splice.app.observability

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splice.app.sources.PerfSourceFiles
import splice.app.sources.observability.PerfAggregateStore
import splice.app.sources.observability.PerfEvidenceFileSource
import splice.core.config.ConfigService
import splice.core.perf.observation.NoPerfObserver
import splice.core.perf.observation.PerfAggregateSource
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfObserver
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.perf.observation.aggregate.PerfAccumulator
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
import splice.usage.perf.RequestProjects
import splice.usage.perf.history.PerfEvidenceSinkSource
import splice.usage.perf.objectives.PerfAlertEngine
import splice.usage.perf.objectives.PerfObjectiveStore
import splice.usage.perf.query.PerfQueryService
import java.io.IOException
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
    private val exportConfig = bootConfig.perfExportConfig()
    val exports = PerfExports(
        exportConfig,
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
    private val nativeExportEnabled = exportConfig.enabled && "traces" in exportConfig.signals
    val nativeTraceExporter = if (enabled && nativeExportEnabled) {
        exports.nativeSpans()
    } else {
        null
    }
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
            connectTimeoutMillis = ALERT_HTTP_TIMEOUT_MS
            requestTimeoutMillis = ALERT_HTTP_TIMEOUT_MS
            socketTimeoutMillis = ALERT_HTTP_TIMEOUT_MS
        }
    }
    private val delivery = PerfWebhookDelivery(AlertDelivery(alerts, log, scope, deliveryClient), scope, objectives)
    val alertEngine = PerfAlertEngine(query, objectives, delivery)
    val instrumentation = PerfInstrumentationStatus(identity, enabled, PerfStatusRead { diagnosticSnapshot() })
    private val gates = ConcurrentHashMap<String, InflightGate>()
    private var sampler: kotlinx.coroutines.Job? = null
    private val configTicks = Channel<Unit>(Channel.CONFLATED)
    private var configWorker: kotlinx.coroutines.Job? = null
    private val cleanup = PerfCleanup(log)

    private fun diagnosticSnapshot(): PerfStatusSnapshot {
        val sinks = listOf(
            accumulator.status(),
            aggregate.status(),
            evidenceFiles.coverageSink.status(),
            configuration.writes.status(),
        ) +
            evidenceStores.values.take(MAX_DIAGNOSTIC_HEADS).map { it.sink.status() }
        val issues = buildList {
            if (!enabled) add("collection_disabled")
            if (observer.failures.get() > 0) add("observer_failure")
            if (observer.dropped.get() > 0) add("observer_drop")
            if (evidenceStores.size > MAX_DIAGNOSTIC_HEADS) add("sink_status_truncated")
            if (sinks.any { it.failed > 0 || it.dropped > 0 }) add("sink_loss")
            addAll(cleanup.issues())
        }
        return PerfStatusSnapshot(sinks, aggregate.costs.snapshot(), evidence.costs.snapshot(), issues)
    }

    fun gate(head: String, gate: InflightGate) { gates[head] = gate }

    fun start() {
        if (sampler != null) return
        if (enabled) aggregate.openWriter()
        if (enabled) heads.forEach { evidenceFiles.startCoverage(it, identity.bootId) }
        tracking.afterChange = PerfConfigSignal { configTicks.trySend(Unit) }
        tracking.start()
        configWorker = scope.launch {
            for (tick in configTicks) {
                runInterruptible {
                    cleanup.run(
                        "configuration_sample",
                    ) { tracking.drain(); gates.values.forEach(InflightGate::sampleObservations) }
                }
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
            due = if (ended - start < TELEMETRY_SAMPLE_MS) start + TELEMETRY_SAMPLE_MS else ended + TELEMETRY_SAMPLE_MS
            if (!ticker.awaitTick((due - MonoClock.nowMs()).coerceAtLeast(1L))) break
        }
    }

    fun accounts(names: PerfAccountNames) { sources.names = names }

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
        val lease = PerfWorkBudget.acquire(TELEMETRY_SNAPSHOT_BYTES) ?: return
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
    suspend fun close() {
        val caller = currentCoroutineContext()
        withContext(NonCancellable + io) {
            config.effectiveObserver = null
            val jobs = listOfNotNull(sampler, configWorker)
            jobs.forEach { it.cancel() }
            val stopped = withTimeoutOrNull(PRODUCER_STOP_MS) { jobs.forEach { it.join() }; true }
            if (stopped != true) {
                cleanup.failed("producers_pending")
                // Keep sources charged and alive for the remaining producers; a later close can finish the drain.
                caller.ensureActive()
                throw IOException("performance producers did not stop")
            }
            configTicks.close()
            Cancellables.withCleanup(::closeOwners) { caller.ensureActive() }
        }
        caller.ensureActive()
    }

    private fun closeOwners() = cleanup.finish(
        ::finalCheckpoints,
        ::flushOwners,
        ::closeReaders,
        ::closeAdapters,
        { cleanup.run("accumulator") { accumulator.close() } },
    )

    private fun finalCheckpoints() = cleanup.finish(
        { cleanup.run("configuration_drain") { tracking.drain() } },
        { cleanup.run("configuration_checkpoint") { configuration.persist(clock()) } },
        { cleanup.run("aggregate_checkpoint") { if (enabled) checkpoint() } },
    )

    private fun flushOwners() = cleanup.finish(
        { cleanup.run("evidence_stop") { if (enabled) heads.forEach { evidenceFiles.stopCoverage(it) } } },
        {
            cleanup.run("evidence_flush") {
                if (!AsyncFileIo.awaitDirectory(paths.perfEvidenceDir)) cleanup.failed("evidence_unsettled")
            }
        },
        { cleanup.run("evidence") { evidenceFiles.close() } },
        { cleanup.run("aggregate") { aggregate.close() } },
    )

    private fun closeReaders() = cleanup.finish(
        { cleanup.run("export_flush") { if (!exports.flush()) cleanup.failed("export_flush_incomplete") } },
        { cleanup.run("profiles") { profiles.close() } },
        { cleanup.run("alerts") { alertEngine.close() } },
        { cleanup.run("delivery") { delivery.close() } },
    )

    private fun closeAdapters() = cleanup.finish(
        { scope.cancel() },
        { cleanup.run("delivery_client") { deliveryClient.close() } },
        { cleanup.run("exports") { exports.close() } },
        { cleanup.run("objectives") { objectives.close() } },
        { cleanup.run("runtime") { runtime.close() } },
        { cleanup.run("tracking") { tracking.close() } },
    )
}

// Sample every five seconds on the owner worker; delayed work schedules a later tick instead of catching up in a burst.
private const val TELEMETRY_SAMPLE_MS = 5_000L

// Reserve 2 MiB for checkpoint snapshot work so contention skips the checkpoint instead of borrowing unbounded memory.
private const val TELEMETRY_SNAPSHOT_BYTES = 2L * 1_024 * 1_024

// Bound webhook connect, request and socket waits to ten seconds on the alert delivery lane.
private const val ALERT_HTTP_TIMEOUT_MS = 10_000L

// Report at most sixty head sink statuses and mark truncation to bound the status projection.
private const val MAX_DIAGNOSTIC_HEADS = 60

// Wait at most fifteen seconds for producers to stop before proceeding with dependent-owner cleanup.
private const val PRODUCER_STOP_MS = 15_000L
