package splice.provider.codex.diagnostics

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.GatewayCustomCall
import splice.core.util.JsonWire
import splice.provider.codex.CODE_MODE_FIELD_INPUT

internal object CodeModeGatewayCalls {
    fun exec(id: String, source: String): GatewayCustomCall = call(id, "exec", source)

    fun wait(id: String, cellId: String): GatewayCustomCall = call(
        id,
        "wait",
        JsonWire.string(
            buildJsonObject {
                put("cell_id", cellId); put("yield_time_ms", 1000)
            },
        ),
    )

    private fun call(id: String, name: String, source: String): GatewayCustomCall = GatewayCustomCall(
        id,
        name,
        source,
        buildJsonObject {
            put("type", if (name == "exec") "custom_tool_call" else "function_call")
            put("call_id", id); put("name", name)
            put(if (name == "exec") CODE_MODE_FIELD_INPUT else "arguments", source)
        },
    )
}
