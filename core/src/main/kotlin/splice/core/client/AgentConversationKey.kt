package splice.core.client

/** Splice's internal continuity identity within a client session, independent of upstream routing and prompt cache. */
public object AgentConversationKey {
    /** Main-thread keys retain their established identity; subagents qualify the opening with their own id. */
    public fun of(opening: String, agentId: String?): String =
        if (agentId.isNullOrEmpty()) opening else "$opening${0.toChar()}agent${0.toChar()}$agentId"
}
