package splice.launch

import splice.client.login.TokenCaptureSpec

/** How a head's sign-in is reached from inside a Claude Code session: the command, its label, whether it
 *  opens a browser, and the in-session confirmation channel. One value, read by the /login block, the
 *  key-missing advertiser and the resume hook alike. */
public data class LaunchSignIn(
    val loginCommand: String, // shell command that runs THIS head's provider sign-in (e.g. `claudex login`)
    val signInLabel: String, // provider label for the /login UX ("Codex (ChatGPT)", "Grok (xAI)")
    /** False for api-key heads: the /login block reason points at a masked terminal prompt. */
    val signInViaBrowser: Boolean = true,
    /** api-key heads: capture a bare pasted token into the KeyStore (blocked from model context). */
    val tokenCapture: TokenCaptureSpec? = null,
    /** Install the SessionStart key-missing advertiser (daemon sets it only while unconfigured). */
    val advertiseKeySetup: Boolean = false,
    /** Absolute path of this head's login receipt (LoginOutcomeFile) — the channel a DETACHED
     *  sign-in uses to tell the session what happened. Empty = no in-session confirmation. */
    val loginOutcomeFile: String = "",
    /** The head's topology key ("codex"): `splice login <key>` and `<wrapper> login` name the same
     *  sign-in, and the /login hook must find it under either spelling (review 2026-09-14). */
    val headKey: String = "",
)
