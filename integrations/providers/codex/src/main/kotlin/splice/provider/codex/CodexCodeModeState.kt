// NEW: defines the live code-mode record, native history and results; the durable form is CodeModeSnapshots.kt.
package splice.provider.codex

import kotlinx.serialization.json.JsonElement
import splice.core.memory.HeapLease
import splice.core.memory.HeapReservations
import splice.provider.codex.state.CodeModeAccepted
import splice.provider.codex.state.CodeModeRecordSnapshots
import splice.provider.codex.state.CodeModeReplayAnchors
import splice.provider.codex.stream.CodeModeSourceEnds
import splice.provider.codex.stream.CodeModeSourceLease
import splice.provider.codex.stream.CodeModeSourceState
import splice.provider.codex.stream.batch.CodeModeNativeBatch
import splice.provider.codex.stream.source.CodeModeSourceClaims
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

    /** Raw receipts reserved on this record's lease before confirmed delivery admits them to its native tail. */
    val sourceClaims: CodeModeSourceClaims = CodeModeSourceClaims()

    /** Monotonic parked time, nested borrowers, and last positive liveness sample. Never persisted. */
    var cellIdleSince: Long? = null
    var cellBorrowers: Int = 0
    var cellLastAliveAt: Long? = null

    /** First runtime advance for the wall-time line; owned by this record and never persisted. */
    @Volatile var cellStartedAt: Long? = null

    /** The kept upstream post whose response started this script (CodeModeUpstreamLedger), so a script that made
     *  no client call is sent after that post and nowhere else. Persisted with its authoritative ledger identity. */
    @Volatile var postedOn: Long? = null

    /** Identity only; native V8 continuations are never serialized or rerun after restart. */
    var nativeCellId: String? = null
    var nativeCompleted: Boolean = false
    var nativeBatch: CodeModeNativeBatch? = null
    var nativeBatchOwner: CodeModeRecord? = null
    var nativeBatchMembers: List<CodeModeRecord> = emptyList()

    /** Transient admission mode: a native capacity refusal proves the source was never dispatched. */
    var nativeRuntime: Boolean = false

    @Volatile var runtimeDispatching: Boolean = false
    var dispatchStarted: Boolean = false

    /** Exact display receipts, distinct from canonical native notification tool outputs. */
    var deliveredOnly: List<JsonElement> = emptyList()

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

    fun terminal(): Boolean =
        (phase == CodeModePhase.COMPLETED && nativeBatch?.settled != false) || error != null

    /** V4-342: whether the record was abandoned, its history no longer placing it. The conversation went on
     *  upstream on the client's own history, and that stays the history: no later rewrite places the
     *  record. Read off the abandon detail in [error], which completing the record keeps, so a record
     *  abandoned before this rule reads the same from its store. */
    fun abandoned(): Boolean = error?.startsWith(CODE_MODE_ABANDONED) == true

    fun snapshot(): CodeModeRecordSnapshot = CodeModeRecordSnapshots.of(this)
}
