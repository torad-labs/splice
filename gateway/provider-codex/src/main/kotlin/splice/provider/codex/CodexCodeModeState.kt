// NEW: defines the durable record, native-history, result, and expiry state for code mode.
package splice.provider.codex

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.spi.CodeModeResult

@Serializable
internal data class CodeModePersistedState(
    val version: Int = 1,
    val records: List<CodeModeRecordSnapshot> = emptyList(),
    val expired: List<CodeModeExpiredSnapshot> = emptyList(),
)

@Serializable
internal data class CodeModeExpiredSnapshot(
    val key: String,
    val lastDigest: String,
    val resultIds: Set<String>,
    val expiredAt: Long,
)

internal class CodeModeExpiredHistory(
    val entries: MutableList<CodeModeExpiredSnapshot>,
    private val limit: Int,
) {
    fun remember(record: CodeModeRecord, now: Long) {
        entries += CodeModeExpiredSnapshot(
            key = record.key,
            lastDigest = record.lastDigest,
            resultIds = record.clientIds(),
            expiredAt = now,
        )
        while (entries.size > limit) entries.removeAt(0)
    }

    fun trim(records: MutableList<CodeModeRecord>, now: Long): Boolean {
        var changed = false
        while (records.size > limit) {
            val index = records.indexOfFirst(CodeModeRecord::terminal)
            if (index < 0) return changed
            remember(records.removeAt(index), now)
            changed = true
        }
        return changed
    }
}

@Serializable
internal data class CodeModeRecordSnapshot(
    val id: String,
    val key: String,
    val outer: JsonObject,
    val outerCallId: String,
    val source: String,
    val phase: CodeModePhase,
    val pending: List<CodeModePending>,
    val results: Map<String, CodeModeResultSnapshot>,
    val output: String?,
    val error: String?,
    val totalCalls: Int,
    val rounds: Int,
    val updatedAt: Long,
    val lastDigest: String,
    val baselineInputCount: Int = 0,
    val baselineInputDigest: String = "",
    val metadataVersion: Int = 0,
    val baselineLogicalCount: Int = 0,
    val baselineLogicalDigest: String = "",
    val nativeSegments: List<CodeModeNativeSegment> = emptyList(),
    val continuity: List<JsonElement> = emptyList(),
    val continuityReplay: List<CodeModeNativeSegment> = emptyList(),
) {
    fun restore(): CodeModeRecord = CodeModeRecord(
        id = id,
        key = key,
        outer = outer,
        outerCallId = outerCallId,
        source = source,
        phase = when {
            phase == CodeModePhase.ACTIVE -> CodeModePhase.LOST
            staleMetadata() -> CodeModePhase.LOST
            else -> phase
        },
        pending = pending.toMutableList(),
        accepted = CodeModeAccepted(results.mapValues { (id, value) -> value.restore(id) }),
        output = output,
        error = when {
            staleMetadata() ->
                "code-mode replay metadata is unavailable; source was not rerun"
            phase == CodeModePhase.ACTIVE ->
                "code-mode process state was lost after completed client calls: ${results.keys}; source was not rerun"
            else -> error
        },
        totalCalls = totalCalls,
        rounds = rounds,
        updatedAt = updatedAt,
        lastDigest = lastDigest,
        baselineInputCount = baselineInputCount,
        baselineInputDigest = baselineInputDigest,
        metadataVersion = metadataVersion,
        baselineLogicalCount = baselineLogicalCount,
        baselineLogicalDigest = baselineLogicalDigest,
        nativeSegments = nativeSegments,
        continuity = continuity,
        continuityReplay = continuityReplay,
    )

    /** A completed record with old metadata is still terminal — the rewrite omits it (and logs) rather
     *  than refusing the conversation; only an unfinished one has nothing left to resume. */
    private fun staleMetadata(): Boolean =
        metadataVersion != CODE_MODE_METADATA_VERSION && phase != CodeModePhase.COMPLETED
}

internal data class CodeModeRecord(
    val id: String,
    val key: String,
    val outer: JsonObject,
    val outerCallId: String,
    val source: String,
    var phase: CodeModePhase,
    val pending: MutableList<CodeModePending> = mutableListOf(),
    val accepted: CodeModeAccepted = CodeModeAccepted(),
    var output: String? = null,
    var error: String? = null,
    var totalCalls: Int = 0,
    var rounds: Int = 0,
    var updatedAt: Long,
    var lastDigest: String,
    val baselineInputCount: Int,
    val baselineInputDigest: String,
    val metadataVersion: Int,
    val baselineLogicalCount: Int,
    val baselineLogicalDigest: String,
    val nativeSegments: List<CodeModeNativeSegment>,
    val continuity: List<JsonElement>,
    val continuityReplay: List<CodeModeNativeSegment>,
) {
    val results: Map<String, CodeModeResult> get() = accepted.results

    fun visiblePending(): List<CodeModePending> = pending.filter(CodeModePending::exposed)

    fun clientIds(): Set<String> = (results.keys + pending.map(CodeModePending::clientId)).toSet()

    fun terminal(): Boolean = phase == CodeModePhase.COMPLETED || error != null

    fun snapshot(): CodeModeRecordSnapshot = CodeModeRecordSnapshot(
        id = id,
        key = key,
        outer = outer,
        outerCallId = outerCallId,
        source = source,
        phase = phase,
        pending = pending.map { it.copy() },
        results = accepted.snapshot(),
        output = output,
        error = error,
        totalCalls = totalCalls,
        rounds = rounds,
        updatedAt = updatedAt,
        lastDigest = lastDigest,
        baselineInputCount = baselineInputCount,
        baselineInputDigest = baselineInputDigest,
        metadataVersion = metadataVersion,
        baselineLogicalCount = baselineLogicalCount,
        baselineLogicalDigest = baselineLogicalDigest,
        nativeSegments = nativeSegments,
        continuity = continuity,
        continuityReplay = continuityReplay,
    )
}

/**
 * What the client has returned for a record so far: each accepted result with, V4-179, the
 * follow-up wire items (images) it rendered to. One map, so a result and its media can never
 * disagree on their keys or their order — the map's insertion order IS the acceptance order, the
 * order the persisted sequence rides in, and kotlinx keeps a JSON object's key order on both sides.
 */
internal class CodeModeAccepted(entries: Map<String, CodeModeAcceptedResult> = emptyMap()) {
    private val entries: MutableMap<String, CodeModeAcceptedResult> = LinkedHashMap(entries)

    val results: Map<String, CodeModeResult> get() = entries.mapValues { (_, entry) -> entry.result }

    /** null: a LEGACY result, accepted before media was captured — it owns nothing and whatever the
     *  client's history carries for it stays ordinary content. Empty: captured, with no media. */
    fun media(id: String): List<JsonElement>? = entries[id]?.media

    /** Every accepted result's follow-ups, flattened in acceptance order: the durable sequence the
     *  canonical history carries once, right after the record's custom output. */
    fun durableMedia(): List<JsonElement> = entries.values.flatMap { it.media.orEmpty() }

    /** A supplied id absent from [media] is captured as "no media" (an empty list), never left legacy. */
    fun accept(supplied: Map<String, CodeModeResult>, media: Map<String, List<JsonElement>>) {
        supplied.forEach { (id, result) -> entries[id] = CodeModeAcceptedResult(result, media[id].orEmpty()) }
    }

    fun copy(): CodeModeAccepted = CodeModeAccepted(entries)

    fun restore(prior: CodeModeAccepted) {
        entries.clear()
        entries.putAll(prior.entries)
    }

    fun snapshot(): Map<String, CodeModeResultSnapshot> = entries.mapValues { (_, entry) ->
        CodeModeResultSnapshot(entry.result.output, entry.result.isError, entry.media)
    }
}

internal data class CodeModeAcceptedResult(val result: CodeModeResult, val media: List<JsonElement>?)

@Serializable
internal data class CodeModeNativeSegment(
    val logicalOffset: Int,
    val items: List<JsonElement>,
)

@Serializable
internal data class CodeModePending(
    val runtimeId: String,
    val clientId: String,
    val name: String,
    val arguments: JsonObject,
    var exposed: Boolean,
)

/** [media]: V4-179, see [CodeModeAccepted.media]. A v3 file has no such key: it decodes to null,
 *  which is exactly "legacy, not captured" — no metadata-version bump, no invalidated records. */
@Serializable
internal data class CodeModeResultSnapshot(
    val output: String,
    val isError: Boolean,
    val media: List<JsonElement>? = null,
) {
    fun restore(id: String): CodeModeAcceptedResult = CodeModeAcceptedResult(CodeModeResult(id, output, isError), media)
}

@Serializable
internal enum class CodeModePhase { ACTIVE, COMPLETED, LOST }
