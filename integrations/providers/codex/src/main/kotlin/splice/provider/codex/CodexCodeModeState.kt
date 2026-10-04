// NEW: defines the durable record, native-history, result, and expiry state for code mode.
package splice.provider.codex

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.provider.codex.state.CodeModeRecordRestorer
import splice.provider.codex.state.CodeModeReplayAnchors
import splice.provider.codex.stream.CodeModeSourceLease
import splice.provider.codex.stream.CodeModeSourceState
import splice.upstream.codemode.CodeModeResult

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

/** The detail an abandoned record carries (V4-342: [CodeModeRecord.abandoned] reads it back). */
internal const val CODE_MODE_ABANDONED: String = "code-mode history no longer places the running script"

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
    /** Kept outside the already-wide constructor; the store compares it explicitly on save. */
    var issued: List<CodeModeIssuedStep> = emptyList()
    var sessionId: String? = null

    /** Optional hashed session/conversation identity; older snapshots remain independent keys. */
    var conversationId: String? = null
    var nativeBaseId: String? = null
    var replayAnchors: CodeModeReplayAnchors? = null
    var sourceState: CodeModeSourceState? = null

    @Transient var retainedBytes: Long? = null

    @Transient var encodedFieldBytes: Map<String, Long>? = null

    fun restore(): CodeModeRecord = CodeModeRecordRestorer().restore(this)
}

internal data class CodeModeRecord(
    val id: String,
    val key: String,
    var outer: JsonObject,
    val outerCallId: String,
    var source: String,
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
    var nativeSegments: List<CodeModeNativeSegment>,
    var continuity: List<JsonElement>,
    var continuityReplay: List<CodeModeNativeSegment>,
) {
    val results: Map<String, CodeModeResult> get() = accepted.results
    val issued: MutableList<CodeModeIssuedStep> = mutableListOf()

    /** In-memory snapshot order; a failed save cannot undo a later reserved snapshot. */
    var saveGeneration: Long = 0
    var sessionId: String? = null

    /** Optional hashed session/conversation identity; older snapshots remain independent keys. */
    var conversationId: String? = null
    var nativeBaseId: String? = null
    var replayAnchors: CodeModeReplayAnchors? = null
    var nativeParent: CodeModeRecord? = null
    var sourceState: CodeModeSourceState? = null
    var sourceEnd: CodeModeSourceLease? = null

    /** Monotonic parked time, nested borrowers, and last positive liveness sample. Never persisted. */
    var cellIdleSince: Long? = null
    var cellBorrowers: Int = 0
    var cellLastAliveAt: Long? = null

    @Volatile var retainedBytes: Long? = null

    fun visiblePending(): List<CodeModePending> = pending.filter(CodeModePending::exposed)

    fun clientIds(): Set<String> = (results.keys + pending.map(CodeModePending::clientId)).toSet()

    fun terminal(): Boolean = phase == CodeModePhase.COMPLETED || error != null

    /** V4-342: whether the record was abandoned, its history no longer placing it. The conversation went on
     *  upstream on the client's own history, and that stays the history: no later rewrite places the
     *  record. Read off the abandon detail in [error], which completing the record keeps, so a record
     *  abandoned before this rule reads the same from its store. */
    fun abandoned(): Boolean = error?.startsWith(CODE_MODE_ABANDONED) == true

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
    ).also {
        it.issued = issued.toList()
        it.sessionId = sessionId
        it.conversationId = conversationId
        it.nativeBaseId = nativeBaseId
        it.replayAnchors = replayAnchors
        it.sourceState = sourceState
    }
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

/** The exact client-visible step this request digest already received. */
@Serializable
internal data class CodeModeIssuedStep(val requestDigest: String, val calls: List<CodeModePending>)

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
internal enum class CodeModePhase { ACTIVE, COMPLETED, LOST, STARTING }
