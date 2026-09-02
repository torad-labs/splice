// NEW: (DR-155) the outbound vendor minimum-edge policy, shared by the chat and responses dialects
// so one wording and one decision serve both. xAI 400s the WHOLE turn on an image smaller than 8px
// per edge (code=invalid_image), and splice forwarded the client's bytes with no dimension check at
// all — six identical live failures in the DR-152 soak, each losing a turn to one pixel.
//
// FAIL-OPEN BY CONSTRUCTION. [violatedMinimum] answers null for "not proven under the floor", which
// includes every unknown: no floor configured, a url source, empty data, undecodable base64, an
// unrecognised magic, a truncated header, a format added after ImageHeaderProbe was written. Only a
// header this proxy actually READ and found under the floor produces a drop, because the failure
// this repairs is a vendor rejecting content, and guessing in the other direction would have splice
// silently deleting images the vendor would have accepted — a strictly worse defect than the one
// being fixed, and one no operator could see.
package splice.core.media

import splice.core.wire.MediaSource

/** Whether an outbound image is PROVABLY under a vendor's minimum edge, and the phrasing for saying
 *  so. Constructed with the head's configured floor; a null floor disables every code path here,
 *  including the decode, so a provider without the quirk never pays for it and its bytes are
 *  byte-identical to before this existed. */
public class ImageFloor(private val minEdgePx: Int?) {

    private val probe = ImageHeaderProbe()

    /**
     * The floor [source] is proven to sit under, or null when it is not proven to. Non-null is the
     * only value that may drop an image — see this file's header on why the unknowns all answer null.
     */
    public fun violatedMinimum(source: MediaSource?): Int? {
        val min = minEdgePx ?: return null
        val px = dimensions(source) ?: return null
        return min.takeIf { px.width < min || px.height < min }
    }

    /** The model-facing reason for a below-floor drop. ONE wording, so the chat and responses
     *  markers cannot drift into two different stories about the same event — and so a reader of
     *  either transcript learns the actual constraint, not just that something vanished. */
    public fun reason(minPx: Int): String = "image edge below this backend's ${minPx}px minimum"

    // Only base64 sources are probed: a url source's bytes are never in splice's hands (the vendor
    // fetches them itself), so there is nothing to read and nothing to prove.
    private fun dimensions(source: MediaSource?): ImagePx? {
        val data = source?.data
        if (source?.type != BASE64) return null
        return if (data.isNullOrEmpty()) null else probe.probeBase64(data)
    }
}

private const val BASE64 = "base64"
