// NEW: composes the opt-in Codex code-mode protocol bridge and its bounded collaborators.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.turn.GatewayCustomCall
import splice.provider.codex.catalog.CodeModeTurnCatalog
import splice.provider.codex.history.CodeModeHistorySweep
import splice.provider.codex.history.CodeModeUpstreamHistory
import splice.provider.codex.state.CodeModeSessionEnd
import splice.provider.codex.state.diagnostics.CodeModeNativeRetirement
import splice.provider.codex.state.retention.CodeModeHandover
import splice.provider.codex.stream.CodeModeRoundInterceptor
import splice.provider.codex.stream.CodeModeRuntimeRun
import splice.upstream.RoundInterceptor
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.availability.CodeModeAvailabilityPlan
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// why: how often a head's code-mode records are swept with no turn (V4-287). A record goes within
// this long of its ttl, and a dead session's parked cell is reclaimed on the next sweep.
private val SWEEP_INTERVAL: Duration = 5.minutes

/** Codex-only protocol bridge. It schedules client tools but never executes them. [sweepInterval] is
 *  how often its records are swept on a timer (V4-287), shorter only in a test. */
public class CodexCodeModeBridge(
    private val config: CodeModeBridgeConfig,
    sweepInterval: Duration = SWEEP_INTERVAL,
) {
    public data class Turn(
        val sessionId: String,
        val conversationKey: String,
        val model: String,
        val tools: Set<String>,
        val toolResults: List<CodeModeResult> = emptyList(),
        /** How the results' images and legacy markers render (V4-179). */
        val rendering: CodeModeResultRendering = CodeModeResultRendering(),
        /** V4-388: each client tool's description, for the cell's `ALL_TOOLS`. */
        /** Raw, current-execution schemas and metadata, including the enabled ranked discovery function. */
        val catalog: CodeModeTurnCatalog = CodeModeTurnCatalog(),
    ) {
        val descriptions: Map<String, String> get() = catalog.descriptions
        val toolDefinitions: List<CodeModeManual.NestedTool> get() = catalog.definitions
        val toolPolicy: CodeModeToolPolicy get() = catalog.policy
    }

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val run = CodeModeRuntimeRun(config.runtimes)
    internal val registry = CodexCodeModeRegistry(
        config,
        json,
        sweepInterval,
        closeSession = CodeModeSessionEnd(run::closeSession),
    )
    private val retirement = CodeModeNativeRetirement(registry.changes::retireNative)
    private val wire = CodexCodeModeWire(json, config.log, retirement, config.state.replayReasoning)
    private val validation = CodexCodeModeValidation(config)
    internal val mcpSearch = config.state.mcpSearch
    internal val machine = CodexCodeModeMachine(config, registry, validation)
    private val history = CodeModeUpstreamHistory(json, config.state, config.log, config.retention.bytes)
    private val historySweep = CodeModeHistorySweep(history.kept, config)
    internal val driver =
        CodexCodeModeDriver(
            config,
            run,
            registry,
            wire,
            validation,
            machine,
            history,
        )
    internal val resume = CodexCodeModeResume(registry, wire, validation, machine, driver)
    internal val controller = CodexCodeModeTurn(registry, wire, driver, resume, machine, validation, config)
    private val handover = CodeModeHandover(
        registry,
        controller.locks,
        CodeModeSessionEnd { key ->
            driver.streams.stop(key)
            history.forget(key)
        },
        config.log,
    )

    init {
        require(config.retention.perConversation?.let { it > 0 } != false) {
            "code-mode retention.perConversation must be positive"
        }
        require(config.retention.records?.let { it > 0 } != false) { "code-mode retention.records must be positive" }
        require(config.retention.bytes > 0) { "code-mode retention.bytes must be positive" }
        require(config.retention.ttl.isPositive()) { "code-mode ttl must be positive" }
        require(sweepInterval.isPositive()) { "code-mode sweepInterval must be positive" }
        require(config.bounds.maxSourceChars > 0) { "code-mode maxSourceChars must be positive" }
        require(config.bounds.maxOutputChars > 0) { "code-mode maxOutputChars must be positive" }
        require(config.bounds.maxCalls?.let { it > 0 } != false) { "code-mode maxCalls must be positive" }
        require(config.bounds.maxRounds?.let { it > 0 } != false) { "code-mode maxRounds must be positive" }
        historySweep.start()
    }

    /** The files of the conversations this head holds in memory now: their history journals and their records'
     *  journals, with the lock files beside them. A Settings clear or a history cut leaves these on the disk, since
     *  removing them under a running head would leave the next save a journal without its earlier revisions. */
    public fun keptInUse(): Set<Path> = history.kept.inUse() + registry.keptInUse

    public fun interceptor(
        turn: Turn,
        initialOuter: GatewayCustomCall? = null,
        disableParallel: Boolean,
    ): RoundInterceptor {
        val admitted = turn.copy(
            toolResults = turn.toolResults.map(validation::admit),
            rendering = turn.rendering.copy(legacy = turn.rendering.legacy.map(validation::admit)),
        )
        return CodeModeRoundInterceptor(admitted, initialOuter, disableParallel, wire, controller, driver.streams)
    }

    public fun injectTool(request: JsonObject, clientTools: Set<String>): JsonObject =
        injectTool(request, clientTools, CodeModeToolPolicy())

    public fun injectTool(request: JsonObject, clientTools: Set<String>, policy: CodeModeToolPolicy): JsonObject =
        if (availability(policy).codeMode) wire.injectTool(request, clientTools, policy) else request

    /** The same typed preflight used by dispatch, without starting a host or touching conversation history. */
    public fun availability(policy: CodeModeToolPolicy): CodeModeAvailabilityPlan = controller.availability.plan(policy)

    /** Explicit authenticated user action; foreground cancellation alone never calls this operation. */
    public suspend fun interruptActiveCells(sessionId: String, conversationKey: String): CodeModeInterruptOutcome =
        splice.provider.codex.stream.interrupt.CodeModeConversationInterrupts(
            registry,
        ).interrupt(sessionId, conversationKey)

    /** #525 (2026-11-12): the head retires and finishes the turns it is answering beside the next daemon. The
     *  conversations those turns belong to run on here until their last turn ends; every other one is stopped now
     *  and is the next daemon's from here on ([CodeModeHandover]). */
    public fun onHeadRetire() {
        historySweep.stop()
        val held = handover.retire()
        (history.kept.hot() - held).forEach(history::forget)
    }

    public fun onHeadStop() {
        driver.streams.stop()
        try {
            registry.onHeadStop()
        } finally {
            // Head stop releases child JVM workers even when the final durable write needs a retry.
            // The reusable provider opens a fresh runtime after its next start.
            run.end()
        }
    }

    /** Ends daemon ownership, unlike a reusable head stop. Unsaved evidence retains its retry owner. */
    public fun onProviderStop() {
        historySweep.stop()
        registry.timed.finish { onHeadStop() }
    }
}
