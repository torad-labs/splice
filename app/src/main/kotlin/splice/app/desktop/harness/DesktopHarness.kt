// NEW: desktop-harnesses. The programs a Claude Desktop Code-tab session can run on, and the model families whose
// sessions have a choice of program. A session on a GPT model runs on Claude Code through Splice, on the GPT model,
// unless the person opted that family into Codex CLI; a session on an Antigravity model runs on agy (`agy acp`, through
// `splice desktop acp`) while Antigravity is on; every other model runs on Claude Code through Splice. The choices and
// the map they resolve to are DesktopHarnesses'.
package splice.app.desktop.harness

import splice.core.harness.HarnessDefinition
import splice.core.mcp.McpHarness

/** A model family whose Desktop sessions have a harness of their own to choose or switch. [providerFamilies] are the
 *  provider families it claims (ProviderFamilyRule's, or a `family` written in splice.toml), in lowercase: none for one
 *  no splice.toml provider carries. A family that is not [toggleable] is always routed; only its harness changes. */
internal enum class HarnessFamily(
    val wire: String,
    val title: String,
    val description: String,
    val providerFamilies: Set<String>,
    val default: HarnessDefinition,
    val choices: List<HarnessDefinition>,
    val toggleable: Boolean,
) {
    OPENAI(
        "openai",
        "GPT models",
        "Sessions on a ChatGPT or OpenAI model. Claude Code through Splice runs them on the GPT model; Codex CLI is " +
            "for a session launcher that starts Codex instead, and only when you choose it",
        setOf("openai", "codex"),
        McpHarness.CLAUDE.definition,
        listOf(McpHarness.CLAUDE.definition, McpHarness.CODEX.definition),
        toggleable = false,
    ),
    ANTIGRAVITY(
        "antigravity",
        "Antigravity",
        "Sessions on Antigravity's Gemini models, which only agy runs. Off leaves Antigravity out of Desktop and the " +
            "daemon never runs agy",
        emptySet(),
        McpHarness.AGY.definition,
        listOf(McpHarness.AGY.definition),
        toggleable = true,
    ),
    ;

    /** Whether a provider of [family], however splice.toml or ProviderFamilyRule cases it, is one of this family's. */
    fun claims(family: String?): Boolean = family?.lowercase() in providerFamilies
}

/** The families by the ids the file and the API spell them in. */
internal class HarnessWords {
    /** The family [wire] names, or null when none is. */
    fun family(wire: String?): HarnessFamily? = HarnessFamily.entries.firstOrNull { it.wire == wire }
}

/** The daemon's harness map, and what of it the daemon serves now. */
internal interface HarnessTarget {
    val harnesses: DesktopHarnesses

    /** Whether Antigravity's models are served now; null before the door has opened. */
    fun antigravityServed(): Boolean?
}
