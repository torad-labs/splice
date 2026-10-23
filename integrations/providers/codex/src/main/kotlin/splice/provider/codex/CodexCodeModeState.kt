// NEW: defines the live code-mode record, native history and results; the durable form is CodeModeSnapshots.kt.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package splice.provider.codex

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapLease
import splice.core.memory.HeapReservations
import splice.provider.codex.state.CodeModeAccepted
import splice.provider.codex.state.CodeModeRecordSnapshots
import splice.provider.codex.state.CodeModeReplayAnchors
import splice.provider.codex.stream.CodeModeSourceEnds
import splice.provider.codex.stream.CodeModeSourceLease
import splice.provider.codex.stream.CodeModeSourceState
import splice.upstream.codemode.CodeModeResult
import java.lang.ref.WeakReference

/** The detail an abandoned record carries (V4-342: [CodeModeRecord.abandoned] reads it back). */
internal const val CODE_MODE_ABANDONED: String = "code-mode history no longer places the running script"
internal const val CODE_MODE_CLIENT_ID_PREFIX: String = "toolu_splice_"

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

    /** First runtime advance for the wall-time line; owned by this record and never persisted. */
    @Volatile var cellStartedAt: Long? = null

    @Volatile var retainedBytes: Long? = null
    var heapLease: HeapLease? = null

    /** The ledger [heapLease] was charged on, and the snapshots that share it. A payload a mutation takes off the
     *  record stays live while one of them still holds it, so that snapshot is charged it on this ledger until it
     *  goes. */
    var heapBudget: HeapReservations? = null
    val heapSnapshots: MutableList<WeakReference<CodeModeRecordSnapshot>> = mutableListOf()

    fun stampCellStart(now: Long) {
        if (cellStartedAt == null) cellStartedAt = now
    }

    /** Hands the round's upstream lease to its ending, once, as the record leaves the head. */
    fun endSource() {
        CodeModeSourceEnds.defer(sourceEnd)
        sourceEnd = null
    }

    fun visiblePending(): List<CodeModePending> = progress.pending.filter { pending ->
        pending.exposed && !accepted.holds(pending.clientId)
    }

    fun clientIds(): Set<String> = buildSet {
        addAll(accepted.ids())
        progress.pending.forEach { add(it.clientId) }
    }

    /** Whether [id] is one of [clientIds], without building them. */
    fun holdsClientId(id: String): Boolean = accepted.holds(id) || progress.pending.any { it.clientId == id }

    /** Whether any of [ids] is one of [clientIds], without building them. */
    fun holdsAnyClientId(ids: Set<String>): Boolean =
        accepted.holdsAny(ids) || progress.pending.any { it.clientId in ids }

    fun terminal(): Boolean = phase == CodeModePhase.COMPLETED || error != null

    /** V4-342: whether the record was abandoned, its history no longer placing it. The conversation went on
     *  upstream on the client's own history, and that stays the history: no later rewrite places the
     *  record. Read off the abandon detail in [error], which completing the record keeps, so a record
     *  abandoned before this rule reads the same from its store. */
    fun abandoned(): Boolean = error?.startsWith(CODE_MODE_ABANDONED) == true

    fun snapshot(): CodeModeRecordSnapshot = CodeModeRecordSnapshots.of(this)
}

/** The model's exec call that started the script: its raw item, call id and [source], and the [baseline] it sat on. */
internal data class CodeModeOrigin(
    var outer: JsonObject,
    val outerCallId: String,
    var source: String,
    val baseline: CodeModeBaseline,
)

/** What the client's input looked like when the record was created: the whole input and its logical (client-visible)
 *  part, each counted and digested, and the metadata version that wrote them. */
internal data class CodeModeBaseline(
    val inputCount: Int,
    val inputDigest: String,
    val logicalCount: Int,
    val logicalDigest: String,
    val metadataVersion: Int,
)

/** What the script is waiting on and how far it has run: its client calls still [pending], the [output] it has
 *  produced, the calls and rounds it has used, and when and under which request digest it last moved. */
internal data class CodeModeProgress(
    val pending: MutableList<CodeModePending> = mutableListOf(),
    var output: String? = null,
    var completionError: String? = null,
    var totalCalls: Int = 0,
    var rounds: Int = 0,
    var updatedAt: Long,
    var lastDigest: String,
)

/** The model's own items a record carries: the native [segments] placed in the history, and the [continuity]
 *  items with their native [replay] that the next turn repeats. */
internal data class CodeModeNativeContinuity(
    var segments: List<CodeModeNativeSegment>,
    var continuity: List<JsonElement>,
    var replay: List<CodeModeNativeSegment>,
)

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

@Serializable
internal enum class CodeModePhase { ACTIVE, COMPLETED, LOST, STARTING }
