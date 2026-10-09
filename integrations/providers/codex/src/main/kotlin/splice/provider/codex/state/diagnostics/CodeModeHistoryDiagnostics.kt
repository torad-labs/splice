// NEW: request-local native rejection evidence and shared history log context, never journal state.
package splice.provider.codex.state.diagnostics

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.provider.codex.CODE_MODE_RECORD_LOG_CHARS
import splice.provider.codex.CodeModeNativeSegment
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.state.CodeModeHistoryIndex

internal enum class CodeModeNativeBranch(val wire: String) {
    NATIVE_ORDER("nativeOrder"),
    ABSENT("absent"),
    COUNT("counted"),
    PAYLOAD("payload"),
    UNEXPECTED("unexpected-offset"),
    ;

    fun reason(): String = when (this) {
        NATIVE_ORDER -> "code-mode native discovery history was edited: nativeOrder"
        ABSENT -> "code-mode native discovery history could not be placed: absent"
        COUNT -> "code-mode native discovery history could not be placed: counted"
        PAYLOAD -> "code-mode native discovery history was edited: payload"
        UNEXPECTED -> "code-mode native discovery history conflicts with its captured position: unexpected-offset"
    }
}

/** Request-local evidence, never persisted. [following] reports captured metadata, not whether the
 * client retained that witness; null means an unexpected item has no unique captured witness state. */
internal data class CodeModeNativeRejection(
    val following: Boolean?,
    val branch: CodeModeNativeBranch,
    val evidence: CodeModeNativeEvidence? = null,
) {
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
    val evidence: CodeModeNativeEvidence? = null,
)

/** Only numbers and fixed categories survive placement, never anchors, identities or wire items. */
internal data class CodeModeNativeEvidence(
    val ownerDepth: Int,
    val lower: Int,
    val upper: Int,
    val witness: CodeModeNativeWitness,
    val expectedOccurrences: Int = 0,
    val actualOccurrences: Int = 0,
) {
    fun logFields(): String =
        "native_owner_depth=$ownerDepth native_bounds_lo=$lower native_bounds_hi=$upper " +
            "native_witness_source=${witness.source} native_witness_kind=${witness.kind} " +
            "native_witness_resolved=${witness.resolved} " +
            "native_expected_occurrences=$expectedOccurrences native_actual_occurrences=$actualOccurrences"
}

internal object CodeModeNativeEvidenceCapture {
    fun capture(
        index: CodeModeHistoryIndex,
        source: CodeModeRecord,
        record: CodeModeRecord,
        segment: CodeModeNativeSegment,
        bounds: IntRange,
    ): CodeModeNativeEvidence {
        val fromSource = source.replayAnchors?.nativeFollowing?.get(segment.logicalOffset)
        val fromRecord = record.replayAnchors?.nativeFollowing?.get(segment.logicalOffset)
        val witness = fromSource ?: fromRecord
        val resolved = witness?.let { index.resolve(it) }
        val depth = generateSequence(source) { it.nativeParent }
            .indexOfFirst { index.owned(it).firstOrNull() == bounds.last }.takeIf { it >= 0 } ?: 0
        val origin = when {
            fromSource != null -> "source"
            fromRecord != null -> "record"
            else -> "none"
        }
        val item = resolved?.let { index.items.getOrNull(it - 1) } as? JsonObject
        return CodeModeNativeEvidence(
            depth,
            bounds.first,
            bounds.last,
            CodeModeNativeWitness(origin, kind(item), resolved != null),
        )
    }

    private fun kind(item: JsonObject?): String = when (JsonScalars.str(item?.get("type"))) {
        "custom_tool_call" -> "custom_tool_call"
        "custom_tool_call_output" -> "custom_tool_call_output"
        "function_call" -> "function_call"
        "function_call_output" -> "function_call_output"
        "message" -> "message"
        else -> if (item == null) "unknown" else "other"
    }
}

/** A projected restoration result carries native rejection evidence only within its request. */
internal data class CodeModeProjectedRewrite(
    val input: ResponsesCodeModeInput?,
    val error: String? = null,
    val nativeRejection: CodeModeNativeRejection? = null,
)

private const val UNAVAILABLE_EVIDENCE = "native_owner_depth=none native_bounds_lo=none native_bounds_hi=none " +
    "native_witness_source=none native_witness_kind=unknown native_witness_resolved=false " +
    "native_expected_occurrences=none native_actual_occurrences=none"

internal object CodeModeHistoryLog {
    fun context(record: CodeModeRecord, rejection: CodeModeNativeRejection?): String {
        val session = record.sessionId?.take(CODE_MODE_RECORD_LOG_CHARS) ?: "none"
        val evidence = rejection?.evidence?.logFields() ?: UNAVAILABLE_EVIDENCE
        return "session $session" + rejection?.let { " ${it.logFields()} $evidence" }.orEmpty()
    }
}
