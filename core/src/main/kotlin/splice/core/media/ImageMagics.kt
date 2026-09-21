// NEW: (DR-155) the format signatures, split from ImageHeaderProbe.kt with the rest of that file's
// concerns when the concentration wall flagged it.
//
// As LATIN-1 text rather than byte literals: ISO-8859-1 maps every char to the byte of the same
// value, so each string below IS its signature, spelled the way the format's own spec spells it,
// with the printable halves readable as themselves.
//
// EVERY non-printing and every high byte is BACKSLASH-U ESCAPED, and this comment is load-bearing
// rather than decorative: a raw 0x1A or 0x89 sitting in a source file is invisible in a diff, and
// the first editor or tool to normalise it would break the magic with nothing to see in review.
//
// It has already happened once, which is why the rule is written down here. The concentration
// split copied this table out of ImageHeaderProbe.kt and the copy carried RAW U+0089, U+001A,
// U+00FF, U+00D8, U+009D and U+0001 into the file while this very comment claimed they were
// escaped - a published claim the bytes contradicted, found by codex-splice reading the file with
// a byte-level tool rather than an editor that renders them. Check with repr, never by eye.
package splice.core.media

internal class ImageMagics {
    val png: ByteArray = latin1("\u0089PNG\r\n\u001A\n")
    val gif87a: ByteArray = latin1("GIF87a")
    val gif89a: ByteArray = latin1("GIF89a")
    val jpegSoi: ByteArray = latin1("\u00FF\u00D8")
    val riff: ByteArray = latin1("RIFF")
    val vp8Keyframe: ByteArray = latin1("\u009D\u0001\u002A")

    private fun latin1(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)
}
