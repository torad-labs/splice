// NEW: the ~/.claude* path fragments, inherited .claude.json keys, and
// shareable on-disk items. Split from ClaudeConfigMaterializer.kt so
// the writer is not billed for the contract table (concentration, 2026-08-19).
package splice.core.launch

/** On-disk items a head may share by symlinking into the operator's global ~/.claude/<item>.
 *  `sessions` is the cross-session-messaging peer registry: sharing it is what lets every head's
 *  ListAgents see every other head's sessions (the message sockets are already machine-global).
 *
 *  `projects` is the transcript tree, and the SECOND dispositioned escape from head isolation (V4-64,
 *  re-landed by V4-168 on 2026-09-19 after V4-115 removed it): a session started on one head resumes
 *  on any other because every head's picker lists the same tree. The isolation ruling of 2026-09-17
 *  covers head CONFIGURATION (settings, rosters, hooks, MCP); transcripts are shared session state,
 *  and the operator named their removal a regression. Whether a head shares them is the operator's
 *  share/isolate policy, never a forced link or a forced un-link (ProjectsLink's header). */
public val sharedLinkItems: List<String> = listOf(
    Keys.SETTINGS, "agents", "commands", "skills", "hooks", "plugins", Keys.CLAUDE_MD, Keys.MCPS, Keys.SESSIONS,
    Keys.PROJECTS,
)

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
    const val SESSIONS = "sessions"
    const val PROJECTS = "projects"
}
