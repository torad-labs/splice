// NEW: defines the durable form of code mode's state: the persisted file, record and expiry snapshots, and results.
package splice.provider.codex

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapLease
import splice.provider.codex.state.CodeModeRecordRestorer
import splice.provider.codex.state.CodeModeReplayAnchors
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
) {
    @Transient var heapLease: HeapLease? = null
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
    /** Kept outside the already-wide constructor; the store compares it explicitly on save. */
    var completionError: String? = null
    var issued: List<CodeModeIssuedStep> = emptyList()
    var sessionId: String? = null

    /** Optional hashed session/conversation identity; older snapshots remain independent keys. */
    var conversationId: String? = null
    var nativeBaseId: String? = null
    var replayAnchors: CodeModeReplayAnchors? = null
    var sourceState: CodeModeSourceState? = null

    /** Verified model items after the sealed exec, before its runtime output. Older journals have none. */
    var nativeAfterExec: List<JsonElement>? = null

    @Transient var retainedBytes: Long? = null

    @Transient var encodedFieldBytes: Map<String, Long>? = null

    @Transient var heapLease: HeapLease? = null

    fun restore(): CodeModeRecord = CodeModeRecordRestorer().restore(this)
}

/** [media]: V4-179, see [CodeModeAccepted.media]. A v3 file has no such key: it decodes to null,
 *  which is exactly "legacy, not captured" — no metadata-version bump, no invalidated records. */
@Serializable
internal data class CodeModeResultSnapshot(
    val output: String,
    val isError: Boolean,
    val media: List<JsonElement>? = null,
    /** Optional v1 exact-wire item hash; absent legacy data never invents the client's original text. */
    val clientOutputDigest: String? = null,
) {
    fun restore(id: String): CodeModeAcceptedResult =
        CodeModeAcceptedResult(CodeModeResult(id, output, isError), media, clientOutputDigest)
}
