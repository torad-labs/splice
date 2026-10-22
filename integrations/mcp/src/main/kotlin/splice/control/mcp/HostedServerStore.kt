package splice.control.mcp

/** Registry mutations and replacement commits use the same lock and reservation counts. */
internal class HostedServerStore {
    val lock = Any()
    val servers = HashMap<McpIdentity, HostedServer>()

    /** A candidate owns its tuple before publication, so no second server can claim the same child identity. */
    val candidates = HashMap<McpIdentity, HostedServer>()
    val bindings = HashMap<String, McpIdentity>()
    val reserved = HashMap<McpIdentity, Int>()
    val admissions = HashMap<McpIdentity, McpAdmission>()
    var closed = false

    fun admissionRefusal(identity: McpIdentity, admission: McpAdmission?): String? = when {
        closed -> "MCP host is stopping"
        admission == null -> null
        admissions[identity] !== admission ->
            "MCP configuration changed after admission; drain and replace the previous server"
        else -> null
    }

    fun namesOf(identity: McpIdentity): List<String> =
        bindings.filterValues { it == identity }.keys.toList()

    fun rebindBusy(identity: McpIdentity): Boolean {
        if ((reserved[identity] ?: 0) > 0) return true
        val previous = servers[identity] ?: return false
        return when {
            previous.admission.paused -> true
            previous.admission.activeRequests > 0 -> true
            else -> previous.unsettled.isNotEmpty()
        }
    }
}
