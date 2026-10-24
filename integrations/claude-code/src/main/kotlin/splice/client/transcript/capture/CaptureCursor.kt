package splice.client.transcript.capture

import java.util.Base64

private const val CAPTURE_CURSOR_PREFIX = "capture:"

// why: bound decoding and split scratch for the encoded trace turn id and decimal source offset.
private const val MAX_CAPTURE_CURSOR_CHARS = 1_024

// why: after the capture prefix, a cursor contains exactly the encoded turn id and source offset.
private const val CAPTURE_CURSOR_PARTS = 2

internal data class CapturePosition(val turn: String, val offset: Int)

/** A captured page cannot silently continue against a different turn or masquerade as a filesystem cursor. */
internal object CaptureCursor {
    fun owns(cursor: String?): Boolean = cursor?.startsWith(CAPTURE_CURSOR_PREFIX) == true

    fun mint(turn: String, offset: Int): String {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(turn.toByteArray(Charsets.UTF_8))
        return "$CAPTURE_CURSOR_PREFIX$encoded:$offset"
    }

    fun parse(cursor: String): CapturePosition? {
        if (!owns(cursor) || cursor.length > MAX_CAPTURE_CURSOR_CHARS) return null
        return position(cursor.removePrefix(CAPTURE_CURSOR_PREFIX).split(':'))
    }

    private fun position(parts: List<String>): CapturePosition? {
        if (parts.size != CAPTURE_CURSOR_PARTS) return null
        val offset = parts.last().toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return turn(parts.first())?.let { CapturePosition(it, offset) }
    }

    private fun turn(encoded: String): String? = try {
        Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }
}
