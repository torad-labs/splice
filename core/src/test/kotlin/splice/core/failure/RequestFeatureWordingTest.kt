package splice.core.failure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

/** Request-feature refusals keep the exact sentence a person already receives at admission. */
class RequestFeatureWordingTest {
    private val sentences = mapOf(
        RequestFeature.OUTPUT_FORMAT to
            "cannot carry the output format this request asks for, so the turn was not sent",
        RequestFeature.STRUCTURED_OUTPUT to
            "cannot carry the structured output this request asks for, so the turn was not sent",
        RequestFeature.DOCUMENT_CITATIONS to
            "cannot translate the requested document citations, so the turn was not sent",
        RequestFeature.TOOL_CHOICE to
            "cannot guarantee the tool choice this request asks for, so the turn was not sent",
        RequestFeature.TOP_K to
            "cannot carry top_k sampling for this request, so the turn was not sent",
        RequestFeature.STOP_SEQUENCES to
            "cannot carry the stop sequences this request asks for, so the turn was not sent",
        RequestFeature.SERVICE_TIER to
            "cannot carry the service tier this request asks for, so the turn was not sent",
        RequestFeature.SPEED to
            "cannot carry the speed setting this request asks for, so the turn was not sent",
        RequestFeature.CONTAINER to
            "cannot carry the container this request asks for, so the turn was not sent",
        RequestFeature.DIAGNOSTICS to
            "cannot carry the diagnostics this request asks for, so the turn was not sent",
        RequestFeature.INFERENCE_GEO to
            "cannot carry the inference region this request asks for, so the turn was not sent",
        RequestFeature.USER_PROFILE_ID to
            "cannot carry the user profile this request asks for, so the turn was not sent",
        RequestFeature.WORKSPACE_ID to
            "cannot carry the workspace this request asks for, so the turn was not sent",
        RequestFeature.CACHE_CONTROL to
            "cannot carry this request's cache-control breakpoint, so the turn was not sent",
        RequestFeature.CACHE_ONLY_WARMUP to
            "cannot carry a cache-only warmup request, so the turn was not sent",
        RequestFeature.TOOL_STRICTNESS to
            "cannot guarantee strict tool calling for this request, so the turn was not sent",
        RequestFeature.TOOL_ALLOWED_CALLERS to
            "cannot carry this tool's caller restriction, so the turn was not sent",
        RequestFeature.TOOL_TYPE to
            "cannot carry this native tool type, so the turn was not sent",
        RequestFeature.TOOL_INPUT_EXAMPLES to
            "cannot carry this tool's input examples, so the turn was not sent",
        RequestFeature.TOOL_EAGER_INPUT_STREAMING to
            "cannot carry eager tool-input streaming for this request, so the turn was not sent",
        RequestFeature.TOOL_CACHE_CONTROL to
            "cannot carry this tool's cache-control breakpoint, so the turn was not sent",
        RequestFeature.REASONING_CONTEXT to
            "cannot carry this reasoning context, so the turn was not sent",
        RequestFeature.DYNAMIC_TOOL_REMOVAL to
            "cannot enforce a mid-conversation tool removal for this request, so the turn was not sent",
    )

    @Test
    fun `every request feature preserves its exact client-facing refusal across generated head names`() {
        assertEquals(RequestFeature.entries.toSet(), sentences.keys)
        val random = Random(73)
        val heads = listOf("claudex", "claude-grok") + List(24) {
            "head-" + List(random.nextInt(1, 14)) { ('a'.code + random.nextInt(26)).toChar() }.joinToString("")
        }
        val lines = SpliceLines()
        val at = Position(Moment.ADMISSION, streamBegan = false)
        for (feature in RequestFeature.entries) {
            val cause = FailureCause.Splice.Declined(Decline.Untranslatable(feature))
            for (head in heads) {
                val facts = LineFacts(head, null, null, false, RequestFacts(false, false), Instant.EPOCH)
                val line = lines.ending(cause, at, ClientWire.answer(cause, at), facts)
                assertEquals("[splice] $head ${sentences.getValue(feature)}.", line.text, "$feature on $head")
            }
        }
    }
}
