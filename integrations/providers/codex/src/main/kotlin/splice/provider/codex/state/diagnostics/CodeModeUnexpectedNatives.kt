// NEW: unexpected native occurrences retain counted evidence independently of replay emission.
package splice.provider.codex.state.diagnostics

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputDigest
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.state.CodeModeHistoryIndex

internal data class CodeModeNativeClaim(
    val recordId: String,
    val offset: Int,
    val items: List<JsonElement>,
    val following: Boolean,
    val evidence: CodeModeNativeEvidence? = null,
)

internal data class CodeModeUnexpectedNative(
    val segment: ResponsesCodeModeReplay,
    val allowedCount: Int,
    val expectedCount: Int,
)

/** Classifies only observed input: canonical emissions never supply evidence for a missing occurrence. */
internal class CodeModeUnexpectedNatives(
    private val codec: CodexCodeModeHistoryCodec,
    private val index: CodeModeHistoryIndex,
    private val replay: Map<Int, List<JsonElement>>,
    private val native: List<ResponsesCodeModeReplay>,
) {
    private val payloads = index.payloads

    fun rejection(
        record: CodeModeRecord,
        issue: CodeModeUnexpectedNative,
        claims: List<CodeModeNativeClaim>,
    ): CodeModeNativeRejection {
        val segment = issue.segment
        val known = segment.items.map(payloads::token).toSet()
        val matched = claims.filter { claim -> claim.items.any { payloads.token(it) in known } }
        val witness = matched.map(CodeModeNativeClaim::following).distinct().singleOrNull()
        val expected = claims.distinctBy { it.offset to it.items.map(payloads::token) }.sumOf { claim ->
            claim.items.count { payloads.token(it) in known }
        }
        val boundary = index.boundary(record) ?: 0
        val actual = replay.filterKeys { it < boundary }.values.sumOf { items ->
            items.count { payloads.token(it) in known }
        }
        val evidence = matched.map(CodeModeNativeClaim::evidence).distinct().singleOrNull()
            ?.copy(expectedOccurrences = expected, actualOccurrences = actual)
        // A restored parent may be missing; surviving claims are not a complete history denominator.
        val item = segment.items.single()
        val type = when (codec.string(item as? JsonObject, "type")) {
            "tool_search_call" -> "tool_search_call"
            "tool_search_output" -> "tool_search_output"
            "reasoning" -> "reasoning"
            "message" -> "message"
            else -> "other"
        }
        return CodeModeNativeRejection(
            witness,
            CodeModeNativeBranch.UNEXPECTED,
            evidence,
            CodeModeUnexpectedNativeEvidence(
                type = type,
                logicalOffset = segment.logicalOffset,
                payloadDigest = InputDigest.hex(item),
                ownerBoundary = index.boundary(record),
                allowedCount = issue.allowedCount,
                expectedCount = issue.expectedCount,
            ),
        )
    }

    fun find(records: List<CodeModeRecord>, claims: List<CodeModeNativeClaim>): CodeModeUnexpectedNative? {
        val allowed = mutableMapOf<Int, MutableList<JsonElement>>()
        claims.forEach { allowed.getOrPut(it.offset) { mutableListOf() } += it.items }
        records.forEach { record ->
            val boundary = index.boundary(record)
            if (boundary != null) {
                record.carry.replay.forEach {
                    allowed.getOrPut(boundary + it.logicalOffset) { mutableListOf() } += it.items
                }
            }
        }
        return native.firstNotNullOfOrNull { segment ->
            val expected = allowed[segment.logicalOffset].orEmpty()
            val expectedTokens = expected.map(payloads::token).toSet()
            segment.items.firstOrNull { payloads.token(it) !in expectedTokens }?.let { item ->
                CodeModeUnexpectedNative(
                    segment = segment.copy(items = listOf(item)),
                    allowedCount = expectedTokens.size,
                    expectedCount = expected.size,
                )
            }
        }
    }
}
