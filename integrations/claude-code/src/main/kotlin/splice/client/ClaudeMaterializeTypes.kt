// NEW: the materialize request/result DTOs. Split from
// ClaudeConfigMaterializer.kt so the writer is not billed for the
// spec surface (concentration, 2026-08-19). Same-package — callers
// keep splice.client.{MaterializeResult,ClaudePolicy,MaterializeSpec}.
package splice.client

import kotlinx.serialization.json.JsonElement
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
 *  modelOptionsCache is the catalog for .claude.json; defaultModel is the pinned fallback. A null
 *  availableModelIds leaves the client's saved choice intact and writes no head roster or picker cache. */
public data class MaterializeSpec(
    val configDir: Path,
    val policy: ClaudePolicy,
    val availableModelIds: List<String>?,
    val defaultModel: String,
    val modelOptionsCache: JsonElement,
    val statuslineCommand: String,
    /** How the head's provider sign-in is offered inside the client. */
    val signIn: MaterializeSignIn = MaterializeSignIn(),
)
