// NEW: request-local native rejection evidence and shared history log context, never journal state.
package splice.provider.codex.state.diagnostics

import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.provider.codex.CODE_MODE_RECORD_LOG_CHARS
import splice.provider.codex.CodeModeRecord

internal enum class CodeModeNativeBranch(val wire: String) {
    NATIVE_ORDER("nativeOrder"),
    ABSENT("absent"),
    COUNT("counted"),
    PAYLOAD("payload"),
    UNEXPECTED("unexpected-offset"),
}

/** Request-local evidence, never persisted. [following] reports captured metadata, not whether the
 * client retained that witness; null means an unexpected item has no unique captured witness state. */
internal data class CodeModeNativeRejection(val following: Boolean?, val branch: CodeModeNativeBranch) {
    fun logFields(): String {
        val witness = when (following) {
            true -> "present"
            false -> "absent"
            null -> "unknown"
        }
        return "native_following=$witness native_branch=${branch.wire}"
    }
}

internal data class CodeModeNativePosition(
    val offset: Int?,
    val following: Boolean,
    val branch: CodeModeNativeBranch? = null,
)

/** A projected restoration result carries native rejection evidence only within its request. */
internal data class CodeModeProjectedRewrite(
    val input: ResponsesCodeModeInput?,
    val error: String? = null,
    val nativeRejection: CodeModeNativeRejection? = null,
)

internal object CodeModeHistoryLog {
    fun context(record: CodeModeRecord, rejection: CodeModeNativeRejection?): String {
        val session = record.sessionId?.take(CODE_MODE_RECORD_LOG_CHARS) ?: "none"
        return "session $session" + rejection?.let { " ${it.logFields()}" }.orEmpty()
    }
}
