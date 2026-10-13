// NEW: retry default is total (Marcos's law): xAI's own refusals get their own plan, never the generic curve.
package splice.provider.grok

import splice.core.wire.HttpStatus
import splice.upstream.failure.VendorRefusal
import splice.upstream.failure.VendorRefusals

// xAI's code for an image it will not read (DR-155: "Image dimensions 1x1 are too small. Both width and height must
// be at least 8 pixels."), as the 400 body spells it.
private val INVALID_IMAGE = Regex(""""code"\s*:\s*"invalid_image"""")

/** A 400 `invalid_image` is xAI refusing an image in the request, and a re-send carries the same image. The image
 *  floor (GrokQuirks.minImageEdgePx) drops what it can measure before the send; one it cannot measure can still meet
 *  this, and the generic curve re-sent it. The client gets xAI's 400 at once, in xAI's words. */
internal class GrokRefusals : VendorRefusals {
    override fun refusal(status: Int, body: String): VendorRefusal? =
        if (status == HttpStatus.BAD_REQUEST && INVALID_IMAGE.containsMatchIn(body)) {
            VendorRefusal("xAI refused an image in the request, and a re-send carries the same image", status)
        } else {
            null
        }
}
