// NEW: desktop-harnesses. The programs a Claude Desktop Code-tab session can run on, and the model families whose
// sessions have a choice of program. A session on a GPT model runs on Claude Code through Splice, on the GPT model,
// unless the person opted that family into Codex CLI; a session on an Antigravity model runs on agy (`agy acp`, through
// `splice desktop acp`) while Antigravity is on; every other model runs on Claude Code through Splice. The choices and
// the map they resolve to are DesktopHarnesses'.
package splice.app.desktop.harness

/** A program a Desktop session runs on. [wire] is its id in `desktop-harnesses.json` and on the API. */
internal enum class DesktopHarness(val wire: String, val title: String, val description: String) {
    CLAUDE_CODE(
        "claude-code",
        "Claude Code (Splice)",
        "Claude Code started through Splice's gateway, on the model the session picked",
    ),
    CODEX(
        "codex",
        "Codex CLI",
        "OpenAI's Codex CLI, started by a session launcher that reads Splice's harness map",
    ),
    ANTIGRAVITY(
        "antigravity",
        "Antigravity",
        "Google's agy, started through `splice desktop acp` in place of Claude Code",
    ),
}

/** A model family whose Desktop sessions have a harness of their own to choose or switch. [providerFamilies] are the
 *  provider families it claims (ProviderFamilyRule's, or a `family` written in splice.toml), in lowercase: none for one
 *  no splice.toml provider carries. A family that is not [toggleable] is always routed; only its harness changes. */
internal enum class HarnessFamily(
    val wire: String,
    val title: String,
    val description: String,
    val providerFamilies: Set<String>,
    val default: DesktopHarness,
    val choices: List<DesktopHarness>,
    val toggleable: Boolean,
) {
    OPENAI(
        "openai",
        "GPT models",
        "Sessions on a ChatGPT or OpenAI model. Claude Code through Splice runs them on the GPT model; Codex CLI is " +
            "for a session launcher that starts Codex instead, and only when you choose it",
        setOf("openai", "codex"),
        DesktopHarness.CLAUDE_CODE,
        listOf(DesktopHarness.CLAUDE_CODE, DesktopHarness.CODEX),
        toggleable = false,
    ),
    ANTIGRAVITY(
        "antigravity",
        "Antigravity",
        "Sessions on Antigravity's Gemini models, which only agy runs. Off leaves Antigravity out of Desktop and the " +
            "daemon never runs agy",
        emptySet(),
        DesktopHarness.ANTIGRAVITY,
        listOf(DesktopHarness.ANTIGRAVITY),
        toggleable = true,
    ),
    ;

    /** Whether a provider of [family], however splice.toml or ProviderFamilyRule cases it, is one of this family's. */
    fun claims(family: String?): Boolean = family?.lowercase() in providerFamilies
}

/** The harnesses and families by the ids the file and the API spell them in. */
internal class HarnessWords {
    /** The harness [wire] names, or null when none is. */
    fun harness(wire: String?): DesktopHarness? = DesktopHarness.entries.firstOrNull { it.wire == wire }

    /** The family [wire] names, or null when none is. */
    fun family(wire: String?): HarnessFamily? = HarnessFamily.entries.firstOrNull { it.wire == wire }
}

/** The daemon's harness map, and what of it the daemon serves now. */
internal interface HarnessTarget {
    val harnesses: DesktopHarnesses

    /** Whether Antigravity's models are served now; null before the door has opened. */
    fun antigravityServed(): Boolean?
}
