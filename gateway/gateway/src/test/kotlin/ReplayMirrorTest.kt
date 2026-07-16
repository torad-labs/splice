// PORT-OF: reasoning/mirror.mjs + replay.mjs pins @ 4ca99f7. The BYTE-COMPAT fixtures below
// were produced by the LIVE Node encodeReasoningEnvelope on 2026-07-16 — a tag/version/order
// drift strands in-flight transcripts, so encode must reproduce them byte-for-byte.
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.wire.TextBlock
import splice.core.wire.ThinkingBlock
import splice.gateway.reasoning.decodeReasoningEnvelope
import splice.gateway.reasoning.encodeReasoningEnvelope
import splice.gateway.reasoning.extractThinking
import splice.gateway.reasoning.mirrorInto
import splice.gateway.reasoning.mirrorWireText

// node -e "import('./server/src/reasoning/replay.mjs').then(m => console.log(m.encodeReasoningEnvelope(...)))"
private const val NODE_WITH_SUMMARY =
    "eyJ0YWciOiJzcGxpY2UtcmVhc29uaW5nIiwidiI6MSwiaXRlbSI6eyJpZCI6InJzX2ZpeCIsImVuY3J5cHRlZF9jb250ZW50IjoiRU5DMTIzIiwic3VtbWFyeSI6W3sidHlwZSI6InN1bW1hcnlfdGV4dCIsInRleHQiOiJzdW0ifV19fQ=="
private const val NODE_NO_SUMMARY =
    "eyJ0YWciOiJzcGxpY2UtcmVhc29uaW5nIiwidiI6MSwiaXRlbSI6eyJpZCI6InJzX2ZpeDIiLCJlbmNyeXB0ZWRfY29udGVudCI6IkVOQzQ1NiJ9fQ=="

private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

class ReplayMirrorTest {

    @Test
    fun `encode is byte-identical to the Node envelope - with and without summary`() {
        assertEquals(
            NODE_WITH_SUMMARY,
            encodeReasoningEnvelope(
                obj(
                    """{"id":"rs_fix","encrypted_content":"ENC123",
                        "summary":[{"type":"summary_text","text":"sum"}]}""",
                ),
            ),
        )
        assertEquals(
            NODE_NO_SUMMARY,
            encodeReasoningEnvelope(obj("""{"id":"rs_fix2","encrypted_content":"ENC456","summary":[]}""")),
        )
    }

    @Test
    fun `decode round-trips a Node envelope and always carries a summary array`() {
        val decoded = decodeReasoningEnvelope(NODE_NO_SUMMARY)!!
        assertEquals("reasoning", decoded["type"]?.jsonPrimitive?.content)
        assertEquals("rs_fix2", decoded["id"]?.jsonPrimitive?.content)
        assertEquals("ENC456", decoded["encrypted_content"]?.jsonPrimitive?.content)
        assertEquals("[]", decoded["summary"].toString())
        val withSummary = decodeReasoningEnvelope(NODE_WITH_SUMMARY)!!
        assertTrue(withSummary["summary"].toString().contains("summary_text"))
    }

    @Test
    fun `decode rejects foreign, garbled, and incomplete payloads`() {
        assertNull(decodeReasoningEnvelope(null))
        assertNull(decodeReasoningEnvelope(""))
        assertNull(decodeReasoningEnvelope("not-base64!!"))
        val foreignTag = java.util.Base64.getEncoder()
            .encodeToString("""{"tag":"other","v":1,"item":{"id":"x","encrypted_content":"y"}}""".toByteArray())
        assertNull(decodeReasoningEnvelope(foreignTag))
        val missingId = java.util.Base64.getEncoder()
            .encodeToString("""{"tag":"splice-reasoning","v":1,"item":{"encrypted_content":"y"}}""".toByteArray())
        assertNull(decodeReasoningEnvelope(missingId))
    }

    @Test
    fun `mirror gates - compact off, non-text off, short off, emits wire format`() = runTest {
        val texts = mutableListOf<String>()
        val sink = sinkCapturing(texts)
        assertFalse(mirrorInto(sink, "long enough reasoning summary", "text", compact = true))
        assertFalse(mirrorInto(sink, "long enough reasoning summary", "thinking", compact = false))
        assertFalse(mirrorInto(sink, "too short", "text", compact = false))
        assertTrue(mirrorInto(sink, "  long enough reasoning summary  ", "text", compact = false))
        assertEquals(listOf("\n[reasoning summary]\nlong enough reasoning summary\n"), texts)
        assertEquals("\n[reasoning summary]\nx\n", mirrorWireText(" x "))
    }

    @Test
    fun `extractThinking joins thinking blocks with paragraph breaks`() {
        val joined = extractThinking(
            listOf(
                ThinkingBlock(" first "),
                TextBlock("not thinking"),
                ThinkingBlock(""),
                ThinkingBlock("second"),
            ),
        )
        assertEquals("first\n\nsecond", joined)
    }
}

private fun sinkCapturing(texts: MutableList<String>): splice.spi.WireSink = object : splice.spi.WireSink {
    private var next = 0
    override suspend fun openText() = splice.core.index.WireBlockIndex(next++)
    override suspend fun openThinking() = splice.core.index.WireBlockIndex(next++)
    override suspend fun openTool(id: String, name: String) = splice.core.index.WireBlockIndex(next++)
    override suspend fun textDelta(index: splice.core.index.WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: splice.core.index.WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: splice.core.index.WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: splice.core.index.WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) {
        texts.add(text)
    }
    override suspend fun addRedactedThinking(data: String) = Unit
}
