package splice.app.observability

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import splice.app.sources.PerfSourceFiles
import splice.core.config.ConfigService
import splice.core.perf.observation.NoPerfObserver
import splice.core.perf.observation.PerfRecorder
import splice.core.util.Cancellables
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.MonoClock
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.observability.export.PerfExports
import splice.observability.profile.ProfileManager
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.retry.InflightGate
import splice.usage.alerts.AlertDelivery
import splice.usage.alerts.AlertStore
import splice.usage.alerts.PerfWebhookDelivery
import splice.usage.perf.RequestProjects
import splice.usage.perf.objectives.PerfAlertEngine
import splice.usage.perf.objectives.PerfObjectiveStore
import splice.usage.perf.query.PerfQueryService
import java.io.IOException

/** The daemon's single composition owner. Construction happens before head producers; no profile starts here. */
internal class PerfTelemetry(
    private val config: ConfigService,
    heads: Set<String>,
    rows: PerfSourceFiles,
    alerts: AlertStore,
    private val log: LogSink,
) {
    private val io = ProcessDispatchers().io()
    private val paths = config.statePaths
    private val clock = WallClock(System::currentTimeMillis)
    private val bootConfig = config.getConfig()
    val enabled = bootConfig.perfCollectionEnabled
    val identity = PerfBuildIdentity.create(PerfConfigRevision.of(bootConfig.asMap()) ?: "unknown:unsupported_policy")
    private val collection = PerfCollection(paths, identity, heads, rows, enabled, clock)
    private val exportConfig = bootConfig.perfExportConfig()
    val exports = PerfExports(exportConfig, identity, collection, clock)
    private val nativeExportEnabled = exportConfig.enabled && "traces" in exportConfig.signals
    val nativeTraceExporter = if (enabled && nativeExportEnabled) {
        exports.nativeSpans()
    } else {
        null
    }
    private val observer = collection.fanout(exports)
    val recorder =
        PerfRecorder(identity, if (enabled) observer else NoPerfObserver, ElapsedClock(MonoClock::nowMs), clock)
    val owners = PerfRuntimeOwners()
    val runtime = PerfRuntimeSampling(identity, recorder, owners, enabled)
    val configuration = PerfConfigHistory(paths.perfConfigurationFile)
    private val tracking = PerfConfigTracking(config, heads, identity.bootId, configuration)
    val sources = collection.sources(configuration).also {
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
    val instrumentation = PerfInstrumentationStatus(
        identity,
        enabled,
        PerfStatusRead {
            collection.diagnostics(configuration, observer, cleanup)
        },
    )
    private val cleanup = PerfCleanup(log)
    private val schedule = PerfTelemetrySchedule(scope, tracking, recorder, log, cleanup, ::tick)

    fun gate(head: String, gate: InflightGate) { schedule.gate(head, gate) }

    fun start() {
        if (schedule.started) return
        collection.start()
        schedule.start()
    }

    fun accounts(names: PerfAccountNames) { sources.names = names }

    private fun tick() {
        tracking.drain()
        configuration.persist(clock())
        if (enabled) {
            runtime.sample()
            schedule.sampleGates()
            collection.checkpoint()
            collection.checkpointCoverage()
        }
        PerfWorkBudget.release(alertEngine.evaluate(clock()))
    }

    fun measured(metric: String, durationMs: Long, owner: String = "control_query") {
        schedule.measured(metric, durationMs, owner)
    }

    /** Called after heads/MCP stop. Flush while source data is still alive, then release dependent owners. */
    suspend fun close() {
        val caller = currentCoroutineContext()
        withContext(NonCancellable + io) {
            config.effectiveObserver = null
            if (!schedule.stop()) {
                cleanup.failed("producers_pending")
                // Keep sources charged and alive for the remaining producers; a later close can finish the drain.
                caller.ensureActive()
                throw IOException("performance producers did not stop")
            }
            Cancellables.withCleanup(::closeOwners) { caller.ensureActive() }
        }
        caller.ensureActive()
    }

    private fun closeOwners() = cleanup.finish(
        ::finalCheckpoints,
        { collection.closePersistence(cleanup) },
        ::closeReaders,
        ::closeAdapters,
        { cleanup.run("accumulator") { collection.close() } },
    )

    private fun finalCheckpoints() = cleanup.finish(
        { cleanup.run("configuration_drain") { tracking.drain() } },
        { cleanup.run("configuration_checkpoint") { configuration.persist(clock()) } },
        { cleanup.run("aggregate_checkpoint") { if (enabled) collection.checkpoint() } },
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

// Bound webhook connect, request and socket waits to ten seconds on the alert delivery lane.
private const val ALERT_HTTP_TIMEOUT_MS = 10_000L
