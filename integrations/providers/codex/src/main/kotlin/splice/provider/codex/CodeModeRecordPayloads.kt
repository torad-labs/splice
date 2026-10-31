@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package splice.provider.codex

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.upstream.codemode.CodeModeResult
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

/** Where a script started in the client's own history: the [count] of items, preamble aside, the client's request
 *  held, and their [digest]. The script ran right after them, so a later history that starts with them still holds
 *  the history it ran on, and one that does not was cut or edited before it. */
@Serializable
internal data class CodeModeClientStart(
    val count: Int,
    val digest: String,
    val acknowledgement: splice.provider.codex.history.CodeModeClientAcknowledgement? = null,
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
) {
    var nativeOutput: JsonElement? = null
    var nativeNotifications: List<JsonElement> = emptyList()
}

/** The model's own items a record carries: the native [segments] placed in the history, and the [continuity]
 *  items with their native [replay] that the next turn repeats. */
internal data class CodeModeNativeContinuity(
    var segments: List<CodeModeNativeSegment>,
    var continuity: List<JsonElement>,
    var replay: List<CodeModeNativeSegment>,
    var nativeAfterExec: List<JsonElement>? = null,
) {
    fun source(): CodeModeContinuity = CodeModeContinuity(continuity, replay, nativeAfterExec)
}

internal data class CodeModeAcceptedResult(
    val result: CodeModeResult,
    val media: List<JsonElement>?,
    val clientOutputDigest: String? = null,
)

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
