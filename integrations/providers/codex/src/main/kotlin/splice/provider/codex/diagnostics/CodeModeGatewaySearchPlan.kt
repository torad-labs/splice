package splice.provider.codex.diagnostics

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.GatewayCustomCall
import splice.provider.codex.CODE_MODE_FIELD_INPUT

internal class CodeModeGatewaySearchPlan(private val nonce: String) {
    private val programs = ArrayDeque(
        listOf(
            "discover-old" to discovery("old"),
            "invoke-old" to invoke("old"),
            "discover-new" to discovery("new"),
            "invoke-new" to invoke("new"),
        ),
    )

    fun next(): GatewayCustomCall? {
        val (suffix, source) = programs.removeFirstOrNull() ?: return null
        val id = "$suffix-$nonce"
        val raw = buildJsonObject {
            put("type", "custom_tool_call"); put("call_id", id); put("name", "exec"); put(CODE_MODE_FIELD_INPUT, source)
        }
        return GatewayCustomCall(id, "exec", source, raw)
    }

    private fun discovery(version: String): String =
        "const result=await tools.tool_search({query:'quasar',limit:1}); " +
            "store('selected-$version',result.tools[0].name); text(result);"

    private fun invoke(version: String): String =
        "const name=load('selected-$version'); text(name); text(await tools[name]({value:'$version-$nonce'}));"
}
