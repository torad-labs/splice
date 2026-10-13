// NEW: retry default is total (Marcos's law): Muse's own refusals get their own plan, never the generic curve. Its
// empty rounds get theirs too (MUSE_CONTINUES_REASONING_ONLY).
package splice.provider.muse

import splice.core.wire.HttpStatus
import splice.upstream.failure.VendorRefusal
import splice.upstream.failure.VendorRefusals

/** A 403 naming the account's plan limit is the same answer to every re-send: the key mint holds an hour on it
 *  (MuseRefresh), and a turn re-sending it on the generic curve only delayed Muse's own words. The client is told the
 *  403 as Muse sent it. A 429 is not this: it keeps the rate-limit cooldown, whatever its words say. */
internal class MuseRefusals(private val oauth: MuseOAuth = MuseOAuth()) : VendorRefusals {
    override fun refusal(status: Int, body: String): VendorRefusal? =
        if (status == HttpStatus.FORBIDDEN && oauth.isPlanTierRejection(body)) {
            VendorRefusal("Muse says this account's plan limit is reached, and a re-send meets the same plan", status)
        } else {
            null
        }
}

/** Muse completes rounds that hold one encrypted reasoning item and nothing else
 *  (status=completed items=[reasoning(summary=0,enc=~1400)]): 418 of 3,538 turns on 2026-10-13, in runs of up to 25 in
 *  one session. The same bytes give the same empty, so the identical re-send healed 39 of 451; carrying on from that
 *  reasoning with a request for the answer changes the input. Muse accepts the shape: its mid-stream re-anchor already
 *  sends reasoning plus a commentary marker and was answered. The app sets the Responses dialect's
 *  continue_reasoning_only quirk from this, so the fact stays in this module. */
public const val MUSE_CONTINUES_REASONING_ONLY: Boolean = true
