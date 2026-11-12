package splice.hub.front

/** Closing new work must still let existing sessions receive server requests and send their settlement traffic. */
class UpgradeAdmission {
    fun permits(request: Request): Boolean = when {
        !request.path.startsWith("/mcp/") -> false
        request.method == "GET" -> true
        request.method == "POST" -> settlement(request.body)
        else -> false
    }

    private fun settlement(body: String): Boolean {
        val msg = JsonText.objectOrNull(body) ?: return false
        val method = Rpc.str(msg, Rpc.METHOD)
        val id = msg[Rpc.ID]
        return (method == null && id != null) || (method == "notifications/cancelled" && id == null)
    }
}
