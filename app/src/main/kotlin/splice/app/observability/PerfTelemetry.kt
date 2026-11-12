package splice.app.observability

import splice.app.sources.PerfSourceFiles
import splice.core.config.ConfigService
import splice.core.util.LogSink
import splice.core.util.PerfWorkPhase
import splice.core.util.WallClock
import splice.observability.export.PerfExports
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.retry.InflightGate
import splice.usage.alerts.AlertStore

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
    val recorder = PerfTelemetryRecorder(identity, enabled, observer, clock).recorder
    val owners = PerfRuntimeOwners()
    val runtime = PerfRuntimeSampling(identity, recorder, owners, enabled)
    val configuration = PerfConfigHistory(paths.perfConfigurationFile)
    private val tracking = PerfConfigTracking(config, heads, identity.bootId, configuration)
    val sources = collection.sources(configuration).also {
        it.enabled = enabled
        it.runtimeSource = runtime
    }
    val inspection = PerfInspection(paths, identity, sources)
    private val scope = LifecycleScope(io)
    private val alerting = PerfAlerting(inspection.query, inspection.objectives, alerts, log, scope)
    val alertEngine = alerting.engine
    val instrumentation = PerfInstrumentationStatus(
        identity,
        enabled,
        PerfStatusRead {
            collection.diagnostics(configuration, observer, cleanup)
        },
    )
    private val cleanup = PerfCleanup(log)
    private val schedule = PerfTelemetrySchedule(scope, tracking, recorder, log, cleanup, ::tick)
    private val shutdown = PerfTelemetryShutdown(io, schedule, config)

    fun gate(head: String, gate: InflightGate) { schedule.gate(head, gate) }

    fun start() {
        if (schedule.started) return
        collection.start()
        schedule.start()
    }

    fun accounts(names: PerfAccountNames) { sources.names = names }

    private fun tick(phase: PerfWorkPhase.Lease) {
        tracking.drain()
        configuration.persist(clock())
        if (enabled) {
            runtime.sample()
            schedule.sampleGates()
            collection.checkpointCoverage()
        }
        alerting.evaluate(clock())
        if (enabled) collection.checkpoint(phase)
    }

    fun measured(metric: String, durationMs: Long, owner: String = "control_query") {
        schedule.measured(metric, durationMs, owner)
    }

    /** Called after heads/MCP stop. Flush while source data is still alive, then release dependent owners. */
    suspend fun close() = shutdown.close(
        { alerting.stopDelivery(cleanup) },
        PerfExportStop { timeoutMs ->
            var completed = false
            cleanup.run("export_flush") {
                completed = exports.closeAndFlushWithin(timeoutMs)
                if (!completed) cleanup.failed("export_flush_incomplete")
            }
            completed
        },
        ::closeOwners,
    )

    private fun closeOwners(phase: PerfWorkPhase.Lease) = cleanup.finish(
        { finalCheckpoints(phase) },
        { collection.closePersistence(cleanup) },
        ::closeReaders,
        ::closeAdapters,
        { cleanup.run("accumulator") { collection.close() } },
    )

    private fun finalCheckpoints(phase: PerfWorkPhase.Lease) = cleanup.finish(
        { cleanup.run("configuration_drain") { tracking.drain() } },
        { cleanup.run("configuration_checkpoint") { configuration.persist(clock()) } },
        { cleanup.run("aggregate_checkpoint") { if (enabled) collection.checkpointFinal(phase) } },
    )

    private fun closeReaders() = cleanup.finish({ cleanup.run("profiles") { inspection.stopProfiles() } })

    private fun closeAdapters() = cleanup.finish(
        { cleanup.run("delivery_client") { alerting.close() } },
        { cleanup.run("exports") { exports.close() } },
        { cleanup.run("objectives") { inspection.close() } },
        { cleanup.run("runtime") { runtime.close() } },
        { cleanup.run("tracking") { tracking.close() } },
    )
}
