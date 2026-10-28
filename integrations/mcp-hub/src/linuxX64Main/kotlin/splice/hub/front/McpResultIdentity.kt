package splice.hub.front

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The actual client call identity survives a front restart with the holder's durable wire ledger. */
internal data class McpResultIdentity(
    val callbackId: String,
    val server: String,
    val tool: String,
    val arguments: JsonObject,
) {
    fun state(): JsonObject = buildJsonObject {
        put("callbackId", callbackId)
        put("server", server)
        put("tool", tool)
        put(RESULT_ARGUMENTS, arguments)
    }
}

internal object McpResultIdentities {
    private val owned = Regex("^toolu_splice_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun fromCall(server: String, request: JsonObject): McpResultIdentity? {
        if (Rpc.str(request, Rpc.METHOD) != "tools/call") return null
        val params = request[Rpc.PARAMS] as? JsonObject ?: return null
        val meta = params["_meta"] as? JsonObject
        return fromFields(
            Rpc.str(meta, "claudecode/toolUseId"),
            server,
            Rpc.str(params, "name"),
            if (RESULT_ARGUMENTS in params) params[RESULT_ARGUMENTS] as? JsonObject else JsonObject(emptyMap()),
        )
    }

    fun restore(saved: JsonObject): McpResultIdentity? = fromFields(
        Rpc.str(saved, "callbackId"),
        Rpc.str(saved, "server"),
        Rpc.str(saved, "tool"),
        saved[RESULT_ARGUMENTS] as? JsonObject,
    )

    private fun fromFields(id: String?, server: String?, tool: String?, arguments: JsonObject?): McpResultIdentity? {
        val callbackId = id?.takeIf(owned::matches)
        return when {
            callbackId == null || arguments == null -> null
            !validNames(server, tool) -> null
            else -> McpResultIdentity(callbackId, requireNotNull(server), requireNotNull(tool), arguments)
        }
    }

    private fun validNames(server: String?, tool: String?): Boolean = !server.isNullOrBlank() && !tool.isNullOrBlank()
}

private const val RESULT_ARGUMENTS = "arguments"
