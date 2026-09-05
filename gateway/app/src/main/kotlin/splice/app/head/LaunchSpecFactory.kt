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

/** How much longer than the daemon's whole-turn cap Claude Code waits before giving up on a
 *  request. Large enough to cover the cancellation seal's travel, small enough that a hung proxy
 *  still surfaces within a minute of its own wall. */
private const val CLIENT_TIMEOUT_GRACE_MS = 60_000L

internal class LaunchSpecFactory(
    private val topology: Topology,
    private val signInPlanner: SignInPlanner,
    private val mgmtKey: MgmtKey,
    private val buildInputs: HeadBuildInputs,
) {
    /**
     * [forwardClientAuth] is [ManagedHeadFactory]'s own `forwardClientAuth` — the STRUCTURAL read of
     * the wired credential (`wired.auth is ClientAuthProvider`), passed in rather than re-derived
     * here. One fact, one derivation: this spec decides whether ANTHROPIC_AUTH_TOKEN is planted into
     * the launched Claude Code process, whether the operator's ambient credentials are stripped from
     * it, and whether /login stays enabled, so it must agree with the door the gateway actually
     * opened. ONE NAME on all four hops (review 2026-08-28, PR 99): it was `forwardClientAuth` here,
     * `clientAuth` on this signature and `nativeClientAuth` on the LaunchSpec, so the `grep -rn
     * forwardClientAuth` audit ClientAuth.kt's header is written around stopped before this leg.
     */
    internal fun launchSpecFor(
        ctx: ProviderBuild,
        controlPort: Int,
        forwardClientAuth: Boolean,
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
            // Derived from the CREDENTIAL, never from the declared `auth.kind` string.
            // ProviderAssembly rejects registered client auth on non-passthrough dialects before
            // launch-spec assembly; direct synthetic calls still consume this resolved flag without
            // reinterpreting the declaration. See ManagedHeadFactory.forwardClientAuth.
            forwardClientAuth = forwardClientAuth,
            pinnedModel = head.pinnedModel,
            discoveryPrefix = head.discoveryPrefix,
            availableModelIds = ctx.catalog.availableModelIds(),
            modelLabels = ctx.catalog.models.associate { it.id to it.label.ifEmpty { it.id } },
            modelSlots = head.models.orEmpty().mapNotNull { model ->
                model.slot?.let { slot -> model.id to slot }
            }.toMap(),
            contextWindow = ctx.catalog.contextWindowFor(head.pinnedModel),
            // The client's request timeout is DERIVED from the head's whole-turn cap (never a
            // second hand-maintained number): the proxy's wall is the one that names the verdict.
            apiTimeoutMs = ctx.watchdog.totalCap.inWholeMilliseconds + CLIENT_TIMEOUT_GRACE_MS,
            modelOptionsCache = buildInputs.modelOptionsCache(ctx.catalog),
            statuslineCommand = "curl -sS --data-binary @- http://127.0.0.1:$controlPort/statusline/$key",
            // The installed wrapper (`<command> login`) runs this head's provider sign-in; the
            // materialized /login command + UserPromptSubmit hook route the user here. api-key
            // heads additionally capture a bare pasted token, and advertise the flow at session
            // start ONLY while the key is unconfigured (re-materialized each launch).
            loginCommand = signIn.command,
            signInLabel = signIn.label,
            signInViaBrowser = signIn.viaBrowser,
            // The CAPABILITY rides ungated; whether it materializes is decided per LAUNCH by
            // LaunchService against ManagedHead.keyPresence (DR-81 — this used to bake the
            // boot-time key check in, so `splice key set` never disarmed the paste-capture hook:
            // the review-of-#75 overwrite risk, frozen instead of fixed).
            tokenCapture = signIn.tokenCapture,
            // The receipt path MUST match what LoginCommand writes (same StatePaths, same head
            // key), or a detached sign-in reports into a file nothing reads.
            loginOutcomeFile = LoginOutcomeFile.pathFor(StatePaths().stateDir, key).toString(),
            advertiseKeySetup = signIn.tokenCapture != null,
            policy = ClaudePolicy(share = topology.claude.share.toSet(), isolate = head.claude.isolate.toSet()),
            port = head.port,
            inferenceToken = mgmtKey.get(),
        )
    }
}
