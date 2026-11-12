package splice.upstream.codemode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodeModeProtocolTest {
    @Test
    fun `result chunks obey encoded frame ceiling with escaped max text and preserve results`() {
        val results = (1..3).map { index ->
            CodeModeResult(index.toString(), "\u0001".repeat(CodeModeLimits.MAX_TEXT_BYTES))
        }
        assertFalse(CodeModeProtocol.fitsResultFrame(results))

        val chunks = CodeModeProtocol.resultChunks(results, maxPerFrame = CodeModeLimits.MAX_RESULTS_PER_FRAME)

        assertEquals(listOf(2, 1), chunks.map { it.size })
        chunks.forEachIndexed { index, chunk ->
            val encodedSize = if (index == chunks.lastIndex) {
                CodeModeProtocol.encodeFrame(CodeModeProtocol.resultsFrame(chunk)).size
            } else {
                CodeModeProtocol.encodeFrame(CodeModeProtocol.resultsPartFrame(chunk)).size
            }
            assertTrue(encodedSize <= CodeModeLimits.MAX_FRAME_BYTES, "chunk $index encoded to $encodedSize bytes")
        }
        val totalEncodedBytes = chunks.sumOf {
            CodeModeProtocol.encodeFrame(CodeModeProtocol.resultsFrame(it)).size
        }
        assertTrue(totalEncodedBytes > CodeModeLimits.MAX_FRAME_BYTES)
        assertEquals(results, chunks.flatten())
    }
}
