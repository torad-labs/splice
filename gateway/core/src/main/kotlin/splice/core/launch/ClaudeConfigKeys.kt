// NEW: the ~/.claude* path fragments, inherited .claude.json keys, and
// shareable on-disk items. Split from ClaudeConfigMaterializer.kt so
// the writer is not billed for the contract table (concentration, 2026-08-19).
package splice.core.launch

/** On-disk items a head may share by symlinking into the operator's global ~/.claude/<item>.
 *  `sessions` is the cross-session-messaging peer registry: sharing it is what lets every head's
 *  ListAgents see every other head's sessions (the message sockets are already machine-global). It is
 *  the ONE dispositioned escape from head isolation — Claude Code's live-session registry, not head
 *  configuration, carrying no model id and no conversation.
 *
 *  `projects` is deliberately NOT here, and must never come back (V4-115, 2026-09-17). The transcript
 *  tree `--resume` lists WAS shared through 91d68f3e so a session could resume on another head;
 *  measured 2026-09-17 that put 95 transcripts carrying head model ids in the operator's vanilla
 *  ~/.claude tree and broke the vanilla client's own resume. OPERATOR RULING: head configurations
 *  and details must NEVER leak into other heads, their wrappers, or the core claude binary sessions.
 *  Each head now owns a REAL projects tree (ProjectsLink guarantees it), the picker stays bounded by
 *  head, and cross-head `-r SESSION_ID` is an explicit COPY made at launch (ResumeAcrossHeads). */
public val sharedLinkItems: List<String> = listOf(
    Keys.SETTINGS, "agents", "commands", "skills", "hooks", "plugins", Keys.CLAUDE_MD, Keys.MCPS, Keys.SESSIONS,
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
