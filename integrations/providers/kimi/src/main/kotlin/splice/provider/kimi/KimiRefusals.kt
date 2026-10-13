// NEW: retry default is total (Marcos's law): Kimi's own refusals get their own plan, never the generic curve.
package splice.provider.kimi

import splice.core.wire.HttpStatus
import splice.upstream.failure.VendorRefusal
import splice.upstream.failure.VendorRefusals

/** A plan-tier answer (the subscription does not include the model, or caps it at 256k) is the same answer to every
 *  re-send of the same model, so the request is not re-sent. Kimi spells it as a 401, which the client reads as a dead
 *  login, so the client is told 403 with Kimi's own words: the account is fine, its plan is not. */
internal class KimiRefusals(private val oauth: KimiOAuth = KimiOAuth()) : VendorRefusals {
    override fun refusal(status: Int, body: String): VendorRefusal? =
        if (oauth.isPlanTierRejection(body)) {
            VendorRefusal(
                "Kimi's plan does not include this model, and a re-send asks for the same one",
                HttpStatus.FORBIDDEN,
            )
        } else {
            null
        }
}
