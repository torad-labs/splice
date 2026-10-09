// NEW: defines the durable record, native-history, result, and expiry state for code mode.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package splice.provider.codex

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapLease
import splice.core.memory.HeapReservations
import splice.provider.codex.state.CodeModeAccepted
import splice.provider.codex.state.CodeModeRecordRestorer
import splice.provider.codex.state.CodeModeRecordSnapshots
import splice.provider.codex.state.CodeModeReplayAnchors
import splice.provider.codex.stream.CodeModeSourceLease
import splice.provider.codex.stream.CodeModeSourceState
import splice.upstream.codemode.CodeModeResult
import java.lang.ref.WeakReference

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
) {
    @Transient var heapLease: HeapLease? = null
}

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

    @Transient var heapLease: HeapLease? = null

    fun restore(): CodeModeRecord = CodeModeRecordRestorer().restore(this)
}

internal data class CodeModeRecord(
    val id: String,
    val key: String,
    var phase: CodeModePhase,
    var error: String? = null,
    val origin: CodeModeOrigin,
    val progress: CodeModeProgress,
    val carry: CodeModeNativeContinuity,
) {
    val accepted: CodeModeAccepted = CodeModeAccepted()
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
    var heapLease: HeapLease? = null

    /** The ledger [heapLease] was charged on, and the snapshots that share it. A payload a mutation takes off the
     *  record stays live while one of them still holds it, so that snapshot is charged it on this ledger until it
     *  goes. */
    var heapBudget: HeapReservations? = null
    val heapSnapshots: MutableList<WeakReference<CodeModeRecordSnapshot>> = mutableListOf()

    fun visiblePending(): List<CodeModePending> = progress.pending.filter(CodeModePending::exposed)

    fun clientIds(): Set<String> = (results.keys + progress.pending.map(CodeModePending::clientId)).toSet()

    fun terminal(): Boolean = phase == CodeModePhase.COMPLETED || error != null

    /** V4-342: whether the record was abandoned, its history no longer placing it. The conversation went on
     *  upstream on the client's own history, and that stays the history: no later rewrite places the
     *  record. Read off the abandon detail in [error], which completing the record keeps, so a record
     *  abandoned before this rule reads the same from its store. */
    fun abandoned(): Boolean = error?.startsWith(CODE_MODE_ABANDONED) == true

    fun snapshot(): CodeModeRecordSnapshot = CodeModeRecordSnapshots.of(this)
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
internal data class CodeModeIssuedStep(
    val requestDigest: String,
    val calls: List<CodeModePending>,
    /** Client-echo matching only. Terminal model continuity remains the sole upstream replay payload. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val deliveredText: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val deliveredNative: List<JsonElement>? = null,
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
internal enum class CodeModePhase { ACTIVE, COMPLETED, LOST, STARTING }
