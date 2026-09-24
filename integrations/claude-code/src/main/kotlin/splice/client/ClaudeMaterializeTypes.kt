// NEW: the materialize request/result DTOs. Split from
// ClaudeConfigMaterializer.kt so the writer is not billed for the
// spec surface (concentration, 2026-08-19). Same-package — callers
// keep splice.client.{MaterializeResult,ClaudePolicy,MaterializeSpec}.
package splice.client

import kotlinx.serialization.json.JsonElement
import splice.client.login.TokenCaptureSpec
import java.nio.file.Path

public data class MaterializeResult(val configDir: Path, val models: Int, val mcpServers: Int)

public data class ClaudePolicy(
    val share: Set<String>,
    val isolate: Set<String>,
) {
    /**
     * Does the policy share [item]? Alias-aware, so the friendly config vocabulary (settings,
     * mcps, claude_md) matches the on-disk item names (settings.json, mcps, CLAUDE.md). isolate
     * wins over share for any alias.
     */
    internal fun shares(item: String): Boolean {
        val aliases = when (item.lowercase()) {
            Keys.SETTINGS -> setOf(Keys.SETTINGS, "settings")
            "claude.md" -> setOf(Keys.CLAUDE_MD, "claude_md", "claude.md", "claudemd")
            Keys.MCPS -> setOf(Keys.MCPS, "mcp")
            else -> setOf(item)
        }
        return aliases.any { it in share } && aliases.none { it in isolate }
    }

    /** Whether a head's `.claude.json` servers are written from the operator's canonical home at each
     *  launch: the materializer's own answer, which the MCP census reads too (v0.4.0 mcp review). */
    public fun sharesMcp(): Boolean = shares(Keys.MCPS)
}

/** Everything a single head needs materialized. availableModelIds REPLACES the picker;
 *  modelOptionsCache is the catalog for .claude.json; defaultModel is the pinned fallback. */
public data class MaterializeSpec(
    val configDir: Path,
    val policy: ClaudePolicy,
    val availableModelIds: List<String>,
    val defaultModel: String,
    val modelOptionsCache: JsonElement,
    val statuslineCommand: String,
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
