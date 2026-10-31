package splice.provider.codex.stream.batch

import kotlinx.serialization.json.JsonElement
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec

/** A native response records every model item before its serial tool results and queued notifications. */
internal object CodeModeBatchHistory {
    fun continuity(record: CodeModeRecord): List<JsonElement>? {
        if (!CodeModeBatchLinks.multiple(record)) return null
        val batch = checkNotNull(record.nativeBatch)
        return if (batch.ordinal == 0) checkNotNull(batch.items) else emptyList()
    }

    fun outputs(record: CodeModeRecord, codec: CodexCodeModeHistoryCodec): List<JsonElement>? {
        if (!CodeModeBatchLinks.multiple(record)) return null
        val owner = checkNotNull(record.nativeBatchOwner)
        if (record.id != owner.id) return emptyList()
        val members = owner.nativeBatchMembers
        return members.flatMap { listOf(codec.customOutput(it)) + it.accepted.durableMedia() } +
            members.flatMap { it.progress.nativeNotifications }
    }

    fun finished(record: CodeModeRecord): Boolean? {
        if (!CodeModeBatchLinks.multiple(record)) return null
        val owner = record.nativeBatchOwner ?: return false
        return owner.nativeBatchMembers.size == owner.nativeBatch?.size && owner.nativeBatchMembers.all {
            it.phase == CodeModePhase.COMPLETED && it.progress.output != null
        }
    }

    fun callbacks(record: CodeModeRecord): Set<String> = if (CodeModeBatchLinks.multiple(record)) {
        checkNotNull(record.nativeBatchOwner).nativeBatchMembers.flatMapTo(mutableSetOf(), CodeModeRecord::clientIds)
    } else {
        record.clientIds()
    }

    fun media(record: CodeModeRecord, id: String): List<JsonElement> =
        record.accepted.media(id) ?: record.nativeBatchMembers.firstNotNullOfOrNull { it.accepted.media(id) }.orEmpty()

    fun owner(record: CodeModeRecord): CodeModeRecord =
        record.nativeBatchOwner?.takeIf { CodeModeBatchLinks.multiple(record) } ?: record

    fun unclaimed(record: CodeModeRecord): Boolean {
        val state = record.sourceState ?: return false
        return state.usage != null && !state.consumed && finished(record) != false
    }

    fun placed(record: CodeModeRecord, items: List<JsonElement>, codec: CodexCodeModeHistoryCodec): Boolean =
        if (CodeModeBatchLinks.multiple(record)) {
            checkNotNull(record.nativeBatchOwner).nativeBatchMembers.all { codec.customOutput(it) in items }
        } else {
            items.any { codec.callId(it) == record.origin.outerCallId }
        }
}
