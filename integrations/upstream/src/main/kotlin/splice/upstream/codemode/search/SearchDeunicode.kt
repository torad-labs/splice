// PORT-OF: deunicode 1.6.2 lib.rs (deunicode_char and AsciiCharsIter), with its exact mapping data.
package splice.upstream.codemode.search

/** Table-driven ASCII transliteration, including upstream's one-character lookahead space rule. */
internal object SearchDeunicode {
    private val pointers = resource("search-deunicode-pointers.bin")
    private val mapping = resource("search-deunicode-mapping.txt").toString(Charsets.US_ASCII)

    fun normalize(text: String): String = buildString(text.length) {
        // deunicode_with_tofu_cow copies the initial bytes below DEL verbatim, including controls.
        val rest = text.indexOfFirst { it.code >= ASCII_PREFIX_LIMIT }.takeIf { it >= 0 } ?: text.length
        append(text, 0, rest)
        val points = text.substring(rest).codePoints().iterator()
        var next = if (points.hasNext()) character(points.nextInt()) else null
        while (next != null) {
            val current = next
            val atEnd = !points.hasNext()
            next = if (atEnd) null else character(points.nextInt())
            val trim = current.length > 1 && current.endsWith(' ') && (atEnd || next?.startsWith(' ') == true)
            append(if (trim) current.dropLast(1) else current)
        }
    }

    private fun character(point: Int): String {
        val index = point * POINTER_BYTES
        if (index + POINTER_BYTES > pointers.size) return "[?]"
        val first = pointers[index].toUByte().toInt()
        val second = pointers[index + 1].toUByte().toInt()
        val length = pointers[index + 2].toUByte().toInt()
        if (length <= INLINE_BYTES) return String(pointers, index, length, Charsets.US_ASCII)
        val offset = first or (second shl Byte.SIZE_BITS)
        return if (offset + length <= mapping.length) mapping.substring(offset, offset + length) else "[?]"
    }

    private fun resource(name: String): ByteArray = checkNotNull(
        javaClass.getResourceAsStream("/splice/upstream/codemode/$name"),
    ) { "Missing CodeMode search normalization data: $name" }.use { it.readBytes() }
}

// deunicode's repr(C) Ptr consists of two inline bytes (or a little-endian u16 offset) and one length byte.
private const val POINTER_BYTES = 3

// why: deunicode 1.6.2 src/lib.rs:155 reads the string from Ptr.chr itself when its length is 2 or less.
private const val INLINE_BYTES = 2

// DEL itself uses the table; upstream's ASCII fast path is strictly less than 0x7f.
private const val ASCII_PREFIX_LIMIT = 0x7f
