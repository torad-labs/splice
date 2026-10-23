// NEW: replay-owner queries share one conversation-locked preparation boundary.
package splice.provider.codex.state.query

import splice.provider.codex.CODE_MODE_CLIENT_ID_PREFIX
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.state.collection.CodeModeRecordKeys

/** Who owns a result's ids: a record of another conversation that claims one, and the ids no record knows. */
internal data class CodeModeResultOwners(
    val foreign: CodeModeRecord?,
    val unknown: Set<String>,
)

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
        val own = CodeModeRecordKeys.of(records, key)
        own.lastOrNull { record ->
            record.phase == CodeModePhase.ACTIVE && matches(record, digest, activeIds, excluded)
        } ?: own.lastOrNull { record ->
            (record.phase == CodeModePhase.LOST || record.phase == CodeModePhase.STARTING) &&
                matches(record, digest, resultIds, excluded)
        }
    }

    fun recordsFor(key: String): List<CodeModeRecord> = withKey(key) { CodeModeRecordKeys.of(records, key) }

    fun completed(key: String): List<CodeModeRecord> = withKey(key) {
        CodeModeRecordKeys.of(records, key).filter { it.phase == CodeModePhase.COMPLETED }
            .filterNot { CodeModeNativeChain.retired(it) && !CodeModeNativeChain.rewritable(it) }
    }

    fun expiredHistory(key: String, digest: String, ids: Set<String>): Boolean = withKey(key) {
        history.entries.any { marker ->
            marker.key == key && (marker.lastDigest == digest || marker.resultIds.any { it in ids })
        }
    }

    fun resultOwners(key: String, ids: Set<String>): CodeModeResultOwners = withKey(key) {
        // why: this reads every conversation's records each turn. Asking a record whether it holds one of [ids]
        // copies nothing, where building each record's id set allocated a map and two sets per record per turn.
        val foreign = if (ids.isEmpty()) {
            null
        } else {
            records.firstOrNull { record -> record.key != key && record.holdsAnyClientId(ids) }
        }
        val claimed = ids.filter { it.startsWith(CODE_MODE_CLIENT_ID_PREFIX) }
        val known = if (claimed.isEmpty()) {
            emptySet()
        } else {
            CodeModeRecordKeys.of(records, key).flatMapTo(HashSet(), CodeModeRecord::clientIds)
        }
        CodeModeResultOwners(foreign, claimed.filterTo(LinkedHashSet()) { it !in known })
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
        (record.progress.lastDigest == digest || (ids.isNotEmpty() && record.holdsAnyClientId(ids)))
}
