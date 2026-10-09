package splice.client

import splice.client.login.TokenCaptureSpec

/** The sign-in half of a [MaterializeSpec]: how a head's provider sign-in is offered inside the client, and
 *  what the materialized hooks do about a missing key. The defaults are a head that offers none. */
public data class MaterializeSignIn(
    // Shell command that runs THIS head's provider sign-in (e.g. `claudex login`). The built-in
    // Anthropic /login is disabled in the launch env; a materialized custom /login command + a
    // UserPromptSubmit hook route the user to this instead. Empty disables the interception.
    val loginCommand: String = "",
    // Human label for the head's provider in the /login UX (e.g. "Codex (ChatGPT)", "Grok (xAI)").
    val signInLabel: String = "",
    // True for browser-OAuth heads; false switches the block reason to the masked-terminal-prompt
    // wording (api-key heads sign in at a console readPassword, not a browser).
    val signInViaBrowser: Boolean = true,
    // api-key heads: capture a BARE provider token pasted as the whole message into the KeyStore,
    // blocked before it reaches the model context. Null disables capture.
    val tokenCapture: TokenCaptureSpec? = null,
    /** Absolute path of this head's login receipt (LoginOutcomeFile). Empty disables the
     *  in-session confirmation — the detached sign-in still works, it just cannot report back. */
    val loginOutcomeFile: String = "",
    /** The head's topology key ("codex"): `splice login <key>` and `<wrapper> login` name the same
     *  sign-in, and the /login hook must find it under either spelling (review 2026-09-14). */
    val headKey: String = "",
    // Install the SessionStart key-missing advertiser. The daemon sets this only while the head's
    // key is unconfigured and re-materializes on every launch, so the advertiser removes itself
    // once the key lands. Requires tokenCapture for the paste instruction to be true.
    val advertiseKeySetup: Boolean = false,
)
