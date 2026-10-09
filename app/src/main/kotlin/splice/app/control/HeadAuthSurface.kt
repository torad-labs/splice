package splice.app.control

import splice.accounts.pool.HeadAccountAuthSource
import splice.accounts.pool.HeadAccountPoolSource
import splice.launch.KeyPresenceProbe

/** How one head authenticates, as the control plane reads it: dialect, live key state and OAuth accounts. */
public data class HeadAuthSurface(
    /** The auth dialect for HeadStatus.authKind (webui) — known at wiring time (chatgpt-oauth|api-key). */
    val authKind: String = "unknown",
    /** DR-81: "does this head hold a working api key RIGHT NOW" — read per /launch, never frozen
     *  into the launch spec (the spec is assembled once at boot; `splice key set` promises live
     *  pickup, and a boot-frozen gate left the paste-your-key capture hook armed against a
     *  working credential). Default true = capture/advertiser stay disarmed, the safe side. */
    val keyPresence: KeyPresenceProbe = KeyPresenceProbe { true },
    /** Head-local OAuth account selections and quotas, projected without credential material. */
    val accountPool: HeadAccountPoolSource? = null,
    /** Per-account masked descriptions; never passed to the unauthenticated statusline. */
    val accountAuth: HeadAccountAuthSource? = null,
)
