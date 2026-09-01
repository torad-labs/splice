// NEW: (DR-155) the format signatures, split from ImageHeaderProbe.kt with the rest of that file's
// concerns when the concentration wall flagged it.
//
// As LATIN-1 text rather than byte literals: ISO-8859-1 maps every char to the byte of the same
// value, so each string below IS its signature, spelled the way the format's own spec spells it,
// with the printable halves readable as themselves. Eight named single-use constants would say
// strictly less than PNG\r\n\n does.
//
// The non-printing bytes stay backslash-u escaped on purpose: a raw 0x1A or 0x01 sitting in a
// source file is invisible in a diff, and the first editor to normalise it would break the magic
// with nothing to see in review.
package splice.core.media

internal class ImageMagics {
    val png: ByteArray = latin1("PNG\r\n\n")
    val gif87a: ByteArray = latin1("GIF87a")
    val gif89a: ByteArray = latin1("GIF89a")
    val jpegSoi: ByteArray = latin1("ÿØ")
    val riff: ByteArray = latin1("RIFF")
    val vp8Keyframe: ByteArray = latin1("*")

    private fun latin1(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)
}
