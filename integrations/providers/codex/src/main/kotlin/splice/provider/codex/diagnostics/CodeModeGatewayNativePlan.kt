package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.GatewayCustomCall
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.provider.codex.CODE_MODE_FIELD_INPUT

/** Model-free FIFO responses; every next source is chosen only after its preceding native output arrives. */
internal class CodeModeGatewayNativePlan(private val family: String, private val nonce: String) {
    private var cellId: String? = null
    private val actions = ArrayDeque(
        when (family) {
            "runtime.session-store" -> listOf(NativeGatewayAction.STORE, NativeGatewayAction.LOAD)
            "runtime.wait-suffix" -> listOf(
                NativeGatewayAction.YIELD,
                NativeGatewayAction.SIBLING,
                NativeGatewayAction.WAIT_SUFFIX,
                NativeGatewayAction.WAIT_EXHAUSTED,
            )
            else -> error("Unknown native gateway probe $family")
        },
    )

    fun next(input: JsonArray): GatewayCustomCall? {
        return when (actions.removeFirstOrNull()) {
            NativeGatewayAction.STORE -> exec("store", "store('x','kept'); text('native-gateway-stored');")
            NativeGatewayAction.LOAD -> exec("load", "text(load('x'));")
            NativeGatewayAction.YIELD -> exec(
                "yield",
                "text('native-gateway-prefix'); yield_control(); " +
                    "await new Promise(r => setTimeout(r,250)); text('native-gateway-suffix');",
            )
            NativeGatewayAction.SIBLING -> {
                cellId = CodeModeGatewayNativeResults.cell(input, "yield-$nonce")
                exec("sibling", "text('native-gateway-sibling');")
            }
            NativeGatewayAction.WAIT_SUFFIX -> wait("suffix")
            NativeGatewayAction.WAIT_EXHAUSTED -> wait("exhausted")
            null -> null
        }
    }

    private fun exec(suffix: String, source: String): GatewayCustomCall = call("$suffix-$nonce", "exec", source)

    private fun wait(suffix: String): GatewayCustomCall = call(
        "$suffix-$nonce",
        "wait",
        JsonWire.string(
            buildJsonObject {
                put("cell_id", checkNotNull(cellId)); put("yield_time_ms", 1000)
            },
        ),
    )

    private fun call(id: String, name: String, source: String): GatewayCustomCall = GatewayCustomCall(
        id,
        name,
        source,
        buildJsonObject {
            put("type", if (name == "exec") "custom_tool_call" else "function_call")
            put(
                "call_id",
                id,
            ); put("name", name); put(if (name == "exec") CODE_MODE_FIELD_INPUT else "arguments", source)
        },
    )
}

internal enum class NativeGatewayAction { STORE, LOAD, YIELD, SIBLING, WAIT_SUFFIX, WAIT_EXHAUSTED }

internal object CodeModeGatewayNativeResults {
    fun cell(input: JsonArray, callId: String): String {
        val output = input.filterIsInstance<JsonObject>().first {
            JsonScalars.str(it, "call_id") == callId && JsonScalars.str(it, "type") == "custom_tool_call_output"
        }.getValue("output") as? JsonArray
        val header = output?.filterIsInstance<JsonObject>()?.firstNotNullOfOrNull { JsonScalars.str(it, "text") }
        return checkNotNull(header).substringAfter("Script running with cell ID ").lineSequence().first()
            .also {
                require(
                    it.isNotBlank() && !it.startsWith("Script"),
                ) { "Native yield has no resumable cell identity" }
            }
    }
}
