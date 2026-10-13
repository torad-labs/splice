// NEW: retry default is total (Marcos's law): Muse's own refusals get their own plan, never the generic curve.
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
