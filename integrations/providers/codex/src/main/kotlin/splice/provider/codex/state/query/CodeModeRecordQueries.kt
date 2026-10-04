// NEW: replay-owner queries share one conversation-locked preparation boundary.
package splice.provider.codex.state.query

import splice.provider.codex.CODE_MODE_CLIENT_ID_PREFIX
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeResultOwners
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeRegistryAccess

/** The one conversation whose query is about to read live or durable owners. */
internal fun interface CodeModeQueryPreparation {
    operator fun invoke(key: String)
}

/** A query prepares only its own key. Cross-key housekeeping remains the timer's responsibility. */
internal class CodeModeRecordQueries(
    private val access: CodeModeRegistryAccess,
    private val records: List<CodeModeRecord>,
    private val history: CodeModeExpiredHistory,
    private val prepare: CodeModeQueryPreparation,
) {
    fun owner(
        key: String,
        digest: String,
        resultIds: Set<String>,
        callbackIds: Set<String>,
        excluded: Set<String>,
    ): CodeModeRecord? = withKey(key) {
        val activeIds = if (callbackIds.isEmpty()) resultIds else resultIds + callbackIds
        records.lastOrNull { record ->
            record.key == key && record.phase == CodeModePhase.ACTIVE &&
                matches(record, digest, activeIds, excluded)
        } ?: records.lastOrNull { record ->
            record.key == key && (record.phase == CodeModePhase.LOST || record.phase == CodeModePhase.STARTING) &&
                matches(record, digest, resultIds, excluded)
        }
    }

    fun recordsFor(key: String): List<CodeModeRecord> = withKey(key) { records.filter { it.key == key } }

    fun completed(key: String): List<CodeModeRecord> = withKey(key) {
        records.filter { it.key == key && it.phase == CodeModePhase.COMPLETED }
    }

    fun expiredHistory(key: String, digest: String, ids: Set<String>): Boolean = withKey(key) {
        history.entries.any { marker ->
            marker.key == key && (marker.lastDigest == digest || marker.resultIds.any { it in ids })
        }
    }

    fun resultOwners(key: String, ids: Set<String>): CodeModeResultOwners = withKey(key) {
        val foreign = records.firstOrNull { record -> record.key != key && record.clientIds().any { it in ids } }
        val known = records.filter { it.key == key }.flatMap(CodeModeRecord::clientIds).toSet()
        val unknown = ids.filter { it.startsWith(CODE_MODE_CLIENT_ID_PREFIX) && it !in known }.toSet()
        CodeModeResultOwners(foreign, unknown)
    }

    private inline fun <T> withKey(key: String, block: () -> T): T = access.withKey(key) {
        prepare(key)
        block()
    }

    /** Exact retry identity or this record's client ids, never merely the same conversation key. */
    private fun matches(
        record: CodeModeRecord,
        digest: String,
        ids: Set<String>,
        excluded: Set<String>,
    ): Boolean = record.id !in excluded &&
        (record.lastDigest == digest || (ids.isNotEmpty() && record.clientIds().any { it in ids }))
}
