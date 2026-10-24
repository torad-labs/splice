package splice.provider.codex.stream.batch

import splice.provider.codex.CodeModeClientStart
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry

/** Explicit unstarted receipts are the only queued sources this owner is allowed to dispatch after restart. */
internal class CodeModeBatchDispatch(private val registry: CodexCodeModeRegistry) {
    fun start(record: CodeModeRecord, client: CodeModeClientStart?) {
        val batch = record.nativeBatch
        if (record.dispatchStarted) return
        check(batch == null || batch.ordinal == 0 || record.sourceState?.itemComplete == true) {
            "queued source did not complete"
        }
        registry.changes.save(record) { it.phase = CodeModePhase.STARTING }
        check(registry.add(record)) { "queued code-mode source cannot be admitted" }
        val acknowledged = client?.takeIf { batch != null && batch.ordinal > 0 }
        registry.changes.save(record) {
            it.dispatchStarted = true
            it.phase = CodeModePhase.LOST
            it.nativeBatch = it.nativeBatch?.copy(dispatched = true)
            if (acknowledged != null) {
                it.replayAnchors = it.replayAnchors?.copy(
                    client = acknowledged,
                )
            }
        }
    }

    fun next(record: CodeModeRecord): CodeModeRecord? {
        val batch = record.nativeBatch ?: return null
        val records = registry.recordsFor(record.key)
        CodeModeBatchLinks.link(records)
        val owner = checkNotNull(record.nativeBatchOwner) { "native response owner is unavailable" }
        val members = owner.nativeBatchMembers
        check(members.size == owner.nativeBatch?.size) { "native response queue is incomplete" }
        check(members.map { it.nativeBatch?.ordinal } == members.indices.toList()) { "native item order is invalid" }
        return members.firstOrNull { CodeModeBatchLinks.pending(it) && it.nativeBatch?.ordinal != batch.ordinal }
    }

    fun settle(record: CodeModeRecord) {
        val owner = record.nativeBatchOwner ?: return
        val members = owner.nativeBatchMembers
        check(members.all { it.phase == CodeModePhase.COMPLETED }) { "native response still owns an unfinished item" }
        members.forEach { member ->
            registry.changes.save(member) { it.nativeBatch = checkNotNull(it.nativeBatch).copy(settled = true) }
        }
    }
}
