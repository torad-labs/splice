// PORT-OF: splice/app/Daemon.kt (launchSpecFor) @ ed5c868 — invariants unchanged: per-head launch
// wiring, next to the ManagedHead it is an argument of. The only remaining user of
// splice.core.launch's ClaudePolicy/LoginOutcomeFile, and the kt-state-paths-single-source ignore
// entry for the per-head CLAUDE_CONFIG_DIR literal ("~/.claude-$key") is re-pointed at this file.
package splice.app.head

import splice.app.SignInPlanner
import splice.app.TopologyLoader
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderBuild
import splice.control.LaunchSpec
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.launch.ClaudePolicy
import splice.core.launch.LoginOutcomeFile
import splice.core.topology.Topology
import java.nio.file.Paths

internal class LaunchSpecFactory(
    private val topology: Topology,
    private val signInPlanner: SignInPlanner,
    private val mgmtKey: MgmtKey,
    private val buildInputs: HeadBuildInputs,
) {
    /**
     * [clientAuth] is [ManagedHeadFactory]'s `forwardClientAuth` — the STRUCTURAL read of the wired
     * credential (`wired.auth is ClientAuthProvider`), passed in rather than re-derived here. One
     * fact, one derivation: this spec decides whether ANTHROPIC_AUTH_TOKEN is planted into the
     * launched Claude Code process, whether the operator's ambient credentials are stripped from it,
     * and whether /login stays enabled, so it must agree with the door the gateway actually opened.
     */
    internal fun launchSpecFor(
        ctx: ProviderBuild,
        controlPort: Int,
        keyPresent: Boolean,
        clientAuth: Boolean,
    ): LaunchSpec {
        val key = ctx.key
        val head = ctx.head
        val providerCfg = ctx.providerCfg
        val configDir = Paths.get(TopologyLoader.expandHome(head.claude.configDir ?: "~/.claude-$key"))
        val signIn = signInPlanner.signInPlan(providerCfg, head, key)
        return LaunchSpec(
            configDir = configDir,
            // A client-auth head serves ANTHROPIC on the client's own login, so the recipe must not
            // strip its credentials, plant the gateway bearer, or disable /login (campaign claude-head).
            // Derived from the CREDENTIAL, never from the declared `auth.kind` string: kind and
            // dialect are independent TOML fields and ClientAuthProvider is built on the
            // anthropic-passthrough arm ALONE, so `kind = "client"` on any other dialect yields a
            // head whose door stays shut while this recipe would have planted the token and left
            // /login enabled anyway. See ManagedHeadFactory.forwardClientAuth for the same read.
            nativeClientAuth = clientAuth,
            pinnedModel = head.pinnedModel,
            availableModelIds = ctx.catalog.availableModelIds(),
            modelLabels = providerCfg.models.associate { it.id to it.label.ifEmpty { it.id } },
            contextWindow = ctx.catalog.contextWindowFor(head.pinnedModel).toInt(),
            // RAW picker ids: two tier rows can share one upstream id (grok-4.6 vs grok-4.6[500k]),
            // and it is the row — not the stripped id — that carries the window the operator picked.
            modelWindows = providerCfg.models.associate { it.id to ctx.catalog.contextWindowFor(it.id).toInt() },
            modelOptionsCache = buildInputs.modelOptionsCache(providerCfg),
            statuslineCommand = "curl -sS --data-binary @- http://127.0.0.1:$controlPort/statusline/$key",
            // The installed wrapper (`<command> login`) runs this head's provider sign-in; the
            // materialized /login command + UserPromptSubmit hook route the user here. api-key
            // heads additionally capture a bare pasted token, and advertise the flow at session
            // start ONLY while the key is unconfigured (re-materialized each launch).
            loginCommand = signIn.command,
            signInLabel = signIn.label,
            signInViaBrowser = signIn.viaBrowser,
            // ONLY while the key is MISSING (review of #75). The capture hook swallows a bare
            // sk-or-… message and stores it; on a head that is already configured that is pure
            // downside — an accidental paste (or discussing a key as the whole message) silently
            // OVERWRITES a working credential and the message never reaches the model. The
            // advertiser below was already gated this way; the hook that acts on the paste was not.
            tokenCapture = signIn.tokenCapture?.takeIf { !keyPresent },
            // The receipt path MUST match what LoginCommand writes (same StatePaths, same head
            // key), or a detached sign-in reports into a file nothing reads.
            loginOutcomeFile = LoginOutcomeFile.pathFor(StatePaths().stateDir, key).toString(),
            advertiseKeySetup = signIn.tokenCapture != null && !keyPresent,
            policy = ClaudePolicy(share = topology.claude.share.toSet(), isolate = head.claude.isolate.toSet()),
            port = head.port,
            inferenceToken = mgmtKey.get(),
        )
    }
}
