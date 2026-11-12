package splice.head

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections

// why: a 1440x900 PNG screenshot is about a megabyte once base64-encoded
private const val SCREENSHOT_BASE64_CHARS = 1024 * 1024

private const val MODEL = "claude-codex--gpt-5.6-sol"

private val OPENING = (
    """{"model":"$MODEL","stream":true,"max_tokens":8000,"system":"SCENARIO:basic","messages":[""" +
        """{"role":"user","content":"look at the page"},""" +
        """{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Read","input":{}}]},""" +
        """{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":["""
    ).toByteArray()

private val CLOSING = """]}]}]}""".toByteArray()
private val IMAGE_OPENING = """{"type":"image","source":{"type":"base64","media_type":"image/png","data":""""
    .toByteArray()
private val IMAGE_CLOSING = """"}}""".toByteArray()
private val COMMA = ",".toByteArray()

/** The V4-360 p20 request: a Messages body of exactly [bytes] whose last tool_result carries screenshots,
 *  base64 in `image` blocks. Held as segments that share one screenshot's bytes, so a 32 MiB body costs the
 *  sender about a megabyte and [stream] never materializes it. */
internal class ScreenshotBody(val bytes: Int) {
    private fun base64(chars: Int) = ByteArray(chars) { 'A'.code.toByte() }

    private val shot = base64(SCREENSHOT_BASE64_CHARS)
    private val parts = segments()

    private fun segments(): List<ByteArray> {
        val image = IMAGE_OPENING.size + SCREENSHOT_BASE64_CHARS + IMAGE_CLOSING.size
        val room = bytes - OPENING.size - CLOSING.size
        val count = ((room + COMMA.size) / (image + COMMA.size)).coerceAtLeast(1)
        // What the whole images leave over rides on the last one's data, so the body lands on [bytes] exactly.
        val spare = room - count * image - (count - 1) * COMMA.size
        val out = mutableListOf(OPENING)
        repeat(count) { index ->
            if (index > 0) out += COMMA
            out += IMAGE_OPENING
            out += if (index == count - 1 && spare != 0) base64(SCREENSHOT_BASE64_CHARS + spare) else shot
            out += IMAGE_CLOSING
        }
        out += CLOSING
        return out
    }

    val text: String get() = parts.joinToString("") { it.decodeToString() }

    fun stream(): InputStream =
        SequenceInputStream(Collections.enumeration(parts.map { ByteArrayInputStream(it) }))
}
