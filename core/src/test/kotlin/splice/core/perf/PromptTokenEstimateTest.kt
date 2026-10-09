// count_tokens sizes a client's context. A base64 screenshot must not count by its text length.
package splice.core.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse

class PromptTokenEstimateTest {

    private fun request(json: String) = AnthropicParse.parseAnthropicBody(json).typed

    @Test
    fun `a request of text alone is its bytes over three`() {
        val json = """{"model":"m","messages":[{"role":"user","content":"hello there"}]}"""
        val estimate = PromptTokenEstimate.forRequest(json.length.toLong(), request(json))
        assertEquals(PromptTokenEstimate.fromBytes(json.length.toLong()), estimate)
    }

    @Test
    fun `a megabyte of base64 image counts as one image, not as 350000 tokens`() {
        val data = "A".repeat(1_000_000)
        val json = """{"model":"m","messages":[{"role":"user","content":[
            {"type":"image","source":{"type":"base64","media_type":"image/png","data":"$data"}},
            {"type":"text","text":"what is this"}]}]}"""
        val estimate = PromptTokenEstimate.forRequest(json.length.toLong(), request(json))
        assertTrue(estimate < 5_000) { "a 1 MB screenshot estimated at $estimate tokens" }
        assertTrue(estimate >= 1_600) { "the image itself still costs tokens: $estimate" }
    }

    @Test
    fun `an image inside a tool result is counted flat too`() {
        val data = "A".repeat(500_000)
        val json = """{"model":"m","messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"t",
            "content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$data"}}]}]}]}"""
        val estimate = PromptTokenEstimate.forRequest(json.length.toLong(), request(json))
        assertTrue(estimate < 5_000) { "a screenshot returned by a tool estimated at $estimate tokens" }
    }
}
