package splice.provider.codex.callbacks.mcp

import kotlinx.serialization.json.JsonObject
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.state.collection.CodeModeRecordKeys
import splice.provider.codex.state.query.CodeModeQueryPreparation
import splice.upstream.codemode.CodeModeResult

/** Only immutable callback identity and accepted values leave the registry's conversation query lock. */
internal data class CodeModeMcpOwner(val recordId: String, val accepted: CodeModeResult?, val call: Call?) {
    data class Call(val clientId: String, val name: String, val arguments: JsonObject)
}

/** Shares the record queries' ordinary conversation lock and preparation boundary. */
internal class CodeModeMcpOwnership(
    private val access: CodeModeRegistryAccess,
    private val records: List<CodeModeRecord>,
    private val prepare: CodeModeQueryPreparation,
) {
    fun snapshot(
        key: String,
        sessionId: String,
        ids: Set<String>,
    ): Map<String, CodeModeMcpOwner> = access.withKey(key) {
        prepare(key)
        val scoped = CodeModeRecordKeys.of(records, key).filter { it.sessionId == sessionId }
        ids.mapNotNull { id ->
            scoped.singleOrNull { it.holdsClientId(id) }?.let { owner -> id to snapshot(owner, id) }
        }.toMap()
    }

    private fun snapshot(record: CodeModeRecord, id: String): CodeModeMcpOwner {
        val accepted = record.results[id]
        val call = if (accepted == null) issued(record, id) else null
        return CodeModeMcpOwner(record.id, accepted, call)
    }

    private fun issued(record: CodeModeRecord, id: String): CodeModeMcpOwner.Call? =
        (record.progress.pending.asSequence() + record.issued.asSequence().flatMap { it.calls.asSequence() })
            .filter { it.clientId == id && it.exposed }
            .distinctBy { it.name to it.arguments }
            .singleOrNull()
            ?.let { CodeModeMcpOwner.Call(it.clientId, it.name, it.arguments) }
}
