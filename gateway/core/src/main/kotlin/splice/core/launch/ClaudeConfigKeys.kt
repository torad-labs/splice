// NEW: the ~/.claude* path fragments, inherited .claude.json keys, and
// shareable on-disk items. Split from ClaudeConfigMaterializer.kt so
// the writer is not billed for the contract table (concentration, 2026-08-19).
package splice.core.launch

/** On-disk items a head may share by symlinking into the operator's global ~/.claude/<item>. */
public val sharedLinkItems: List<String> =
    listOf(Keys.SETTINGS, "agents", "commands", "skills", "hooks", "plugins", Keys.CLAUDE_MD, Keys.MCPS)

/** ~/.claude.json keys carried into a head's isolated state (only when absent locally). */
public val portKeys: List<String> = listOf(
    "verbose", "showSpinnerTree", "tipsHistory", "effortCalloutV2Dismissed",
    "unpinOpus47LaunchEffort", "unpinOpus48LaunchEffort", "unpinFable5LaunchEffort",
    "opusProMigrationComplete", "sonnet1m45MigrationComplete", Keys.ONBOARDING,
    "lastOnboardingVersion", "autoUpdates", "theme",
)

/** The `~/.claude*` path fragments and `.claude.json` keys are the byte-for-byte state contract with
 *  Claude Code; naming them once keeps the contract in a single place instead of duplicated literals. */
internal object Keys {
    const val CLAUDE_DIR = ".claude"
    const val CLAUDE = ".claude"
    const val CLAUDE_JSON = ".claude.json"
    const val SETTINGS = "settings.json"
    const val CLAUDE_MD = "CLAUDE.md"
    const val MCPS = "mcps"
    const val MODEL = "model"
    const val AVAILABLE_MODELS = "availableModels"
    const val STATUS_LINE = "statusLine"
    const val MCP_SERVERS = "mcpServers"
    const val CUSTOM_API_KEY_RESPONSES = "customApiKeyResponses"
    const val ONBOARDING = "hasCompletedOnboarding"
    const val COMMANDS = "commands"
    const val HOOKS = "hooks"
}
