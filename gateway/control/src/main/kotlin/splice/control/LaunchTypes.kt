// NEW: the launch request bag and the exec recipe it produces. Split from
// LaunchService.kt so the assembler is not billed for the DTOs
// (concentration, 2026-08-19). Same-package FQCNs are unchanged.
package splice.control

import kotlinx.serialization.json.JsonElement
import splice.core.launch.ClaudePolicy
import splice.core.launch.TokenCaptureSpec
import java.nio.file.Path

/** What a head needs to produce a launch recipe (supplied by :app at wiring time). */
public data class LaunchSpec(
    val configDir: Path,
    val pinnedModel: String,
    val availableModelIds: List<String>,
    val modelLabels: Map<String, String>, // id -> display label (for the alias slot names)
    /** id -> Claude tier slot ("opus"/"sonnet"/"haiku"/"fable"), declared per row in the head's
     *  catalog. Empty = fall back to [LaunchService]'s positional heuristic, which is what every
     *  catalog used before slots existed and is the reason splice.toml carries an "ORDER IS
     *  LOAD-BEARING" banner. Non-empty = ONLY the declared tiers are emitted — positional order is
     *  fully retired for that head, and an undeclared tier stays un-set rather than pointing a
     *  second alias at an already-claimed model (the 2-model duplication this exists to remove). */
    val modelSlots: Map<String, String> = emptyMap(),
    val contextWindow: Int,
    val modelOptionsCache: JsonElement, // the /model picker option list
    val statuslineCommand: String, // per-head statusline command (…/statusline/<head>)
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
    val policy: ClaudePolicy,
    val port: Int,
    /** Per-install local gateway credential; shared with the head's inbound verifier. */
    val inferenceToken: String,
    /**
     * TRUE for a client-auth head: the client keeps its OWN Anthropic credentials and its own
     * /login (campaign claude-head). Every other head serves a FOREIGN vendor, so the recipe must
     * strip the client's Anthropic session and plant the gateway bearer instead — here that would
     * replace exactly the credential the head forwards upstream, and disabling /login would nail
     * shut the only door that can heal a 401.
     */
    val forwardClientAuth: Boolean = false,
)

public data class LaunchRecipe(
    val env: Map<String, String>,
    val unset: List<String>,
    val argv: List<String>,
    // Non-null only when dangerouslySkipPermissions was engaged — surfaced to the operator via the
    // control log and the /launch response so the danger is never silent.
    val warning: String? = null,
)
