// NEW: composes the opt-in Codex code-mode protocol bridge and its bounded collaborators.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.turn.GatewayCustomCall
import splice.spi.CodeModeResult
import splice.spi.CodeModeRuntime
import splice.spi.RoundInterceptor
import java.nio.file.Path
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private const val DEFAULT_MAX_SOURCE_CHARS: Int = 65_536
private const val DEFAULT_MAX_OUTPUT_CHARS: Int = 1_048_576

public data class CodeModeBridgeConfig(
    val runtime: CodeModeRuntime,
    val stateFile: Path,
    val maxRecords: Int = 128,
    val ttl: Duration = 24.hours,
    val maxSourceChars: Int = DEFAULT_MAX_SOURCE_CHARS,
    val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
    val maxCalls: Int = 64,
    val maxRounds: Int = 32,
    val clock: Clock = Clock.systemUTC(),
)

/** Codex-only protocol bridge. It schedules client tools but never executes them. */
public class CodexCodeModeBridge(private val config: CodeModeBridgeConfig) {
    public data class Turn(
        val sessionId: String,
        val conversationKey: String,
        val model: String,
        val tools: Set<String>,
        val toolResults: List<CodeModeResult> = emptyList(),
    )

    private val json = Json { encodeDefaults = true }
    private val wire = CodexCodeModeWire(json)
    private val registry = CodexCodeModeRegistry(config, json)
    private val validation = CodexCodeModeValidation(config)
    private val machine = CodexCodeModeMachine(config, registry, validation)
    private val driver = CodexCodeModeDriver(config, registry, wire, validation, machine)
    private val resume = CodexCodeModeResume(registry, wire, validation, machine, driver)
    private val controller = CodexCodeModeTurn(registry, wire, driver, resume)

    init {
        require(config.maxRecords > 0) { "code-mode maxRecords must be positive" }
        require(config.ttl.isPositive()) { "code-mode ttl must be positive" }
        require(config.maxSourceChars > 0) { "code-mode maxSourceChars must be positive" }
        require(config.maxOutputChars > 0) { "code-mode maxOutputChars must be positive" }
        require(config.maxCalls > 0) { "code-mode maxCalls must be positive" }
        require(config.maxRounds > 0) { "code-mode maxRounds must be positive" }
    }

    public fun interceptor(
        turn: Turn,
        initialOuter: GatewayCustomCall? = null,
        disableParallel: Boolean,
    ): RoundInterceptor = RoundInterceptor { bodyJson, sink, post ->
        controller.run(CodeModeRunInput(turn, initialOuter, disableParallel, bodyJson, sink, post))
    }

    public fun injectTool(request: JsonObject): JsonObject = wire.injectTool(request)

    public fun onHeadStop(): Unit = registry.onHeadStop()
}
