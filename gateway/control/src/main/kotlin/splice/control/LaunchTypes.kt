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
    val contextWindow: Int,
    /** Raw picker id -> that row's window. Claude Code resolves a non-`claude-` model's context
     *  window from CLAUDE_CODE_MAX_CONTEXT_TOKENS ALONE (cli 2.1.233 `G4u`); the per-model
     *  `context_window` we ship in [modelOptionsCache] is validated on `value`/`label`/
     *  `description` and never read. The window is therefore a property of the PROCESS, not of the
     *  active model — an in-session /model switch cannot move it — so picking a long-context row
     *  has to happen at launch, and this map is what turns that choice into the right env. */
    val modelWindows: Map<String, Int> = emptyMap(),
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
    val nativeClientAuth: Boolean = false,
)

public data class LaunchRecipe(
    val env: Map<String, String>,
    val unset: List<String>,
    val argv: List<String>,
    // Non-null only when dangerouslySkipPermissions was engaged — surfaced to the operator via the
    // control log and the /launch response so the danger is never silent.
    val warning: String? = null,
)
