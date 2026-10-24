// NEW: composes the opt-in Codex code-mode protocol bridge and its bounded collaborators.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.turn.GatewayCustomCall
import splice.provider.codex.history.CodeModeUpstreamHistory
import splice.provider.codex.state.CodeModeSessionEnd
import splice.provider.codex.state.diagnostics.CodeModeNativeRetirement
import splice.provider.codex.stream.CodeModeRoundInterceptor
import splice.provider.codex.stream.CodeModeRuntimeRun
import splice.upstream.RoundInterceptor
import splice.upstream.codemode.CodeModeResult
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
        val descriptions: Map<String, String> = emptyMap(),
    )

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
    internal val machine = CodexCodeModeMachine(config, registry, validation)
    internal val driver =
        CodexCodeModeDriver(
            config,
            run,
            registry,
            wire,
            validation,
            machine,
            CodeModeUpstreamHistory(json, config.state, config.log, config.retention.bytes),
        )
    internal val resume = CodexCodeModeResume(registry, wire, validation, machine, driver)
    internal val controller = CodexCodeModeTurn(registry, wire, driver, resume, machine, validation, config.log)

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
    }

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
        wire.injectTool(request, clientTools)

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
        registry.timed.finish { onHeadStop() }
    }
}
