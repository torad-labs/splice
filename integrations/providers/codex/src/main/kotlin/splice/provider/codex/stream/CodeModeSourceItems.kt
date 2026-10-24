package splice.provider.codex.stream

import splice.core.turn.GatewayCustomCall
import splice.provider.codex.CodeModeBridgeConfig
import splice.upstream.codemode.CodeModeLimits

/** Source and metadata updates remain bound to the admitted item; a completed program cannot change. */
internal class CodeModeSourceItems(private val config: CodeModeBridgeConfig) {
    fun identity(call: GatewayCustomCall, expected: GatewayCustomCall) {
        check(call.callId == expected.callId && call.name == expected.name) { "streamed exec identity changed" }
        expected.raw["id"]?.let { item ->
            check(call.raw["id"] == item) { "streamed exec item identity changed" }
        }
    }

    fun completed(call: GatewayCustomCall, captured: GatewayCustomCall) {
        identity(call, captured)
        check(captured.input == call.input) { "terminal exec source changed its completed item" }
    }

    fun source(text: String) {
        require(text.length <= config.bounds.maxSourceChars && CodeModeLimits.fitsText(text)) {
            "exec source exceeds the size limit"
        }
    }
}
