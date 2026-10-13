// NEW: retry default is total (Marcos's law): xAI's own refusals get their own plan, never the generic curve.
package splice.provider.grok

import splice.core.wire.HttpStatus
import splice.upstream.failure.VendorRefusal
import splice.upstream.failure.VendorRefusals

// xAI's code for an image it will not read (DR-155: "Image dimensions 1x1 are too small. Both width and height must
// be at least 8 pixels."), as the 400 body spells it.
private val INVALID_IMAGE = Regex(""""code"\s*:\s*"invalid_image"""")

// xAI reports an expired or revoked OAuth token as 403 `unauthenticated:bad-credentials`, NOT 401 (grok-dead-head
// incident, 2026-07-18: refresh never fired, the head 403'd every turn until a manual re-login), and its string
// `error` form says the OAuth2 access token could not be validated. Moved here from upstream's FailureRules: a
// vendor's spelling lives in its module. A plan or billing 403 says none of these.
private val EXPIRED_TOKEN = Regex(
    "unauthenticated|bad-credentials|token (is )?(invalid|expired)|(access|oauth2?) token could not be validated",
    RegexOption.IGNORE_CASE,
)

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

    /** A 403 in xAI's expiry words is a dead credential, refreshed under V4-38's freshness rule
     *  (GrokAuthProvider.allowRefreshAfterFailure). */
    override fun isAuthFailure(status: Int, body: String): Boolean =
        status == HttpStatus.FORBIDDEN && EXPIRED_TOKEN.containsMatchIn(body)
}
