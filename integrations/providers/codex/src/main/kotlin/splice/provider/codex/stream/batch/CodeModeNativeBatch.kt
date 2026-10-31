package splice.provider.codex.stream.batch

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord

/** Durable executable item identities and a response owner; no runtime continuation is serialized. */
@Serializable
internal data class CodeModeNativeBatch(
    val ownerId: String,
    val ordinal: Int,
    val dispatched: Boolean = false,
    val size: Int = 1,
    val settled: Boolean = false,
    val items: List<JsonObject>? = null,
)

/** Reconstructed references only. Independent queued records retain the original sealed source on disk. */
internal object CodeModeBatchLinks {
    fun link(records: List<CodeModeRecord>) {
        records.filter { it.nativeBatch != null }.groupBy { it.nativeBatch?.ownerId }.values.forEach { members ->
            val ordered = members.sortedBy { it.nativeBatch?.ordinal }
            val owner = ordered.firstOrNull { it.id == it.nativeBatch?.ownerId }
            ordered.forEach { it.nativeBatchOwner = owner }
            if (owner != null) owner.nativeBatchMembers = ordered
        }
    }

    fun multiple(record: CodeModeRecord): Boolean =
        (record.nativeBatchOwner ?: record).nativeBatch?.size?.let { it > 1 } == true

    fun accepted(record: CodeModeRecord): Boolean =
        record.dispatchStarted || record.sourceState?.itemComplete != false ||
            record.issued.isNotEmpty() || record.results.isNotEmpty()

    fun pending(record: CodeModeRecord): Boolean = record.nativeBatch?.dispatched == false

    fun retained(record: CodeModeRecord): Boolean {
        val members = record.nativeBatchOwner?.nativeBatchMembers.orEmpty().ifEmpty { listOf(record) }
        return members.any {
            it.phase == CodeModePhase.ACTIVE || it.phase == CodeModePhase.STARTING
        }
    }
}
