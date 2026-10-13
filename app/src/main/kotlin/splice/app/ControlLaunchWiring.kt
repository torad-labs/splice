// NEW: the control plane's one LaunchService, moved out of ControlPlane.kt by its own seam, behavior unchanged
// (concentration, Oct 13, splice-lead): the materializer, the transcript copies a resume across heads rewrites, the
// Claude login arm and the MCP hub launch. The login owner and accounts port it builds land in the [ClaudeLoginSlot]
// ControlPlane holds from its first line, at the same moment as before, so every reader that asks later finds them.
package splice.app

import splice.app.auth.claude.ClaudeAccountsPort
import splice.app.auth.claude.ClaudeIdentityRefresh
import splice.app.auth.claude.ClaudeLoginOwner
import splice.app.auth.claude.ClaudeLoginWiring
import splice.app.auth.claude.ClaudePoolChanges
import splice.app.daemon.BootedTopology
import splice.app.daemon.DaemonMaterializer
import splice.app.launch.HookProcessExec
import splice.client.ClaudeConfigMaterializer
import splice.client.mcp.HostedMcpOverlay
import splice.client.mcp.McpAccessKey
import splice.client.mcp.McpHubFront
import splice.client.mcp.McpHubLaunch
import splice.client.mcp.McpHubLive
import splice.client.mcp.McpLaunchPlanner
import splice.client.mcp.McpSharing
import splice.client.mcp.PluginFlagGate
import splice.client.mcp.PluginFlagHolds
import splice.client.resume.AuthHeaderFile
import splice.client.resume.ResumeAcrossHeads
import splice.client.resume.ResumeHookTarget
import splice.client.resume.TranscriptModelRewrite
import splice.client.resume.originals.CopyingOriginals
import splice.client.resume.originals.OriginalsCutoff
import splice.client.resume.originals.TranscriptOriginals
import splice.client.wrap.WrappedHead
import splice.core.TESTED_CLAUDE_CODE
import splice.core.config.TurnKey
import splice.launch.recipe.LaunchService
import splice.upstream.LifecycleScope
import splice.usage.quota.ClientUserAgent
import java.nio.file.Path
import kotlin.time.Duration.Companion.days

// why: the port the MCP hub front listens on, the address shape splice-config set (docs/specs/mcp-hub.md step 6).
private const val MCP_HUB_FRONT_PORT = 3095

/** The Claude login owner and accounts port, null until a Claude head's login machinery is built. Readers capture the
 *  slot, never its values, so each read finds what the launch service wired last. */
internal class ClaudeLoginSlot {
    var owner: ClaudeLoginOwner? = null

    // V4-410 follow-on: the add and remove of a Claude head's subscriptions, read by the accounts port's
    // `client` arm (ClaudeAccountsArm).
    var accounts: ClaudeAccountsPort? = null
}

/** What the Claude login arm is built from, and the [slot] it lands in. */
internal data class ClaudeLoginInputs(
    val scope: LifecycleScope,
    val userAgent: ClientUserAgent,
    val identities: ClaudeIdentityRefresh,
    val poolChanges: ClaudePoolChanges,
    val slot: ClaudeLoginSlot,
)

internal class ControlLaunchWiring(
    /** The daemon's shared environment: its state, its settings, its management key and its log sink. */
    private val daemon: DaemonEnvironment,
    private val topology: BootedTopology,
    /** Whether a Claude Code version holds --plugin-dir-no-mcp, asking for its check when it has no verdict yet. */
    private val pluginFlags: PluginFlagHolds,
    private val login: ClaudeLoginInputs,
) {
    // Views over [daemon], never copies: a stored copy would fix the value at construction.
    private val statePaths get() = daemon.statePaths
    private val config get() = daemon.config
    private val mgmtKey get() = daemon.mgmtKey
    private val log get() = daemon.log

    /** The daemon's one materializer: the real hook exec (V4-103), and the control port the resume hook
     *  (V4-169) calls back on with the turn key's header file — handed over as its SOURCE, written
     *  current by each install, so start() does no file I/O here. Its own member so the start()
     *  budget holds and the call stays greppable. */
    private fun materializer(home: Path, sharing: McpSharing, controlPort: Int): ClaudeConfigMaterializer =
        DaemonMaterializer.build(
            home,
            sharing.rewrite(),
            hookExec = HookProcessExec.exec,
            resumeHook = ResumeHookTarget(controlPort, AuthHeaderFile(TurnKey(mgmtKey)::headerFile)),
        )

    /** The one LaunchService, over the daemon's home: its wrap (V4-129) writes and reads the SAME
     *  home the materializer's DR-102 guard protects, so the vanilla dir wrap targets is the one
     *  the guard refuses to every other head. */
    fun service(home: Path, sharing: McpSharing, controlPort: Int, hub: McpHubLive): LaunchService {
        val materializer = materializer(home, sharing, controlPort)
        val copying = CopyingOriginals { config.getConfig().keepTranscriptCopies }
        val window = OriginalsCutoff {
            System.currentTimeMillis() - config.getConfig().transcriptCopyDays.days.inWholeMilliseconds
        }
        val originals = TranscriptOriginals(statePaths, keeping = copying, cutoff = window, log = log)
        val rewriter = TranscriptModelRewrite(originals = originals)
        val wrap = WrappedHead(home)
        val arm = ClaudeLoginWiring(
            statePaths,
            login.scope,
            wrap,
            log,
            login.userAgent,
            login.identities,
            login.poolChanges,
        ).create(topology.path)
        val owner = arm.owner
        login.slot.owner = owner
        login.slot.accounts = arm.accounts
        owner.places().map { it.head }.distinct().forEach(login.poolChanges::publish)
        return LaunchService(materializer, resumeAcrossHeads = ResumeAcrossHeads(rewriter), wrap = wrap).also {
            it.loginGuard = owner
            it.hubLaunch = McpHubLaunch(
                McpLaunchPlanner(home),
                sharing,
                McpAccessKey(mgmtKey::get),
                McpHubFront(MCP_HUB_FRONT_PORT, statePaths.stateDir.resolve("mcp-hub"), hub = hub),
                log,
                PluginFlagGate(TESTED_CLAUDE_CODE, pluginFlags),
                overlay = HostedMcpOverlay(home, sharing, statePaths.stateDir, log),
            )
        }
    }
}
