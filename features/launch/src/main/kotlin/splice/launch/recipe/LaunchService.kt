// PORT-OF: server/launcher/{assemble-env,ensure-proxy}.mjs exec-recipe @ pre-public-port-baseline, as a daemon
// endpoint (P4-LAUNCH). The bin shim POSTs /launch{head}; the daemon materializes the head's Claude
// config (P5-PREP) and returns the exec recipe. Env recipe restored to Node fidelity — the minimal
// version broke two things: (1) Claude Code fell back to Anthropic /login because it saw a custom
// ANTHROPIC_API_KEY instead of an ANTHROPIC_AUTH_TOKEN bearer; (2) only the pinned model showed
// because CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY wasn't set, so the /model picker never queried
// /v1/models. The bearer stays. Discovery was retired on 2026-08-30 for a materialized roster per head, and came
// back with head-catalog (Nov 19, 2026; Marcos: one list, the Splice gateway's, in every session): a head's command
// starts Claude Code in its gateway mode at the Splice gateway (GatewayMode), in the head's own folder and on the
// head's own model, and splice hands it no list of its own anywhere: no roster, no picker cache, no modelOverrides
// and no tier variables. The gateway's /v1/models is the one list (GatewayModels), and its routing runs a turn on the
// head and login of the model it names. A head that forwards the person's own Claude login, which the gateway never
// carries, keeps its own way to Anthropic, with no list of splice's either; a command another machine serves (LAN
// share) keeps its own address and its tiers.
package splice.launch.recipe

import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudeTiers
import splice.client.ManagedSettings
import splice.client.MaterializeSignIn
import splice.client.MaterializeSpec
import splice.client.SessionHelpers
import splice.client.TrustedLaunch
import splice.client.desktop.FIRST_PARTY_ENV
import splice.client.desktop.GATEWAY_DISCOVERY
import splice.client.desktop.providerSwitches
import splice.client.mcp.HubLaunchArgs
import splice.client.mcp.HubLaunchFor
import splice.client.mcp.McpHubLaunch
import splice.client.mcp.OwnMcpLaunch
import splice.client.resume.MovingTrust
import splice.client.resume.ResumeAcrossHeads
import splice.client.resume.TranscriptModelRewrite
import splice.client.wrap.ClaudeToRun
import splice.client.wrap.WrapStateRead
import splice.client.wrap.WrappedHead
import splice.client.wrap.WrappedLaunch
import splice.core.GATEWAY_VERSION
import splice.core.client.NO_HOLD_HEADER
import splice.core.client.SPLICE_CLIENT_HEADER
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.util.EnvReader
import splice.launch.LaunchOutcome
import splice.launch.LaunchRecipe
import splice.launch.LaunchSpec
import splice.launch.RemoteCommands
import splice.sessions.registry.LaunchMarker
import java.nio.file.Path

// LaunchSpec + LaunchRecipe live in LaunchTypes.kt (concentration, 2026-08-19).

public class LaunchService(
    private val materializer: ClaudeConfigMaterializer,
    private val claudeBinary: String = "claude",
    private val envReader: EnvReader = EnvReader(System::getenv),
    /** V4-115 cross-head `-r SESSION_ID`. A defaulted collaborator, not a wired one: the daemon's
     *  composition root (ControlPlane) constructs LaunchService with a materializer alone, and a
     *  cross-head resume needs no daemon state — only the sibling config dirs the spec carries. */
    private val resumeAcrossHeads: ResumeAcrossHeads = ResumeAcrossHeads(),
    /** V4-129 review: the daemon's ONE wrap of the default `claude` command. The /api/claude-head
     *  routes act on this instance and the /launch route resolves a wrapped `claude` through it, so
     *  the wrap that is written and the wrap a launch reads are one object over one home (ControlPlane
     *  passes the daemon's). Carried here because LaunchService is the one launch object the control
     *  server is handed; the default is the real home, exactly what ClaudeHeadRoutes defaulted to. */
    public val wrap: WrappedHead = WrappedHead(UserHome.dir()),
    /** V4-129: the real absolute claude binary when the default `claude` command is WRAPPED —
     *  app/src/main/dist/bin/splice-launch execs argv[0] by resolving it through PATH, and a wrapped `claude` on PATH
     *  IS the shim, so planting the bare [claudeBinary] string there would make EVERY head's launch
     *  (not only a wrapped one) recurse into itself. Defaulted to [wrap], a REAL reader (not a no-op),
     *  so this self-protection holds from day one: the state file it reads simply does not exist
     *  until wrap is used, at which point it answers bare `claude` exactly like today. Read PER LAUNCH,
     *  never cached — wrap/unwrap can flip between two requests — and its answer is acted on where argv[0] is chosen. */
    private val wrapState: WrapStateRead = wrap,
    /** Which moves are in flight (MovingTrust), so a resume of one carries the trust he granted in the folder it is
     *  moving out of. A defaulted collaborator that reads the daemon's own state dir; ControlPlane passes the
     *  daemon's, and a tree with no record in flight answers null for every launch. */
    private val moving: MovingTrust = MovingTrust(StatePaths().movingTrustDir),
) {
    /** What an organisation manages for a command's config tree and this machine, which can set telemetry of its own:
     *  the platform's managed settings, their drop-ins and the tree's remote settings (#791). Claude Code's own on this
     *  machine; a test assigns one over a folder of its own so it never reads the machine's. */
    internal var managed: ManagedSettings = ManagedSettings()

    /** Assigned by the daemon's native login owner before routes serve requests. */
    public var loginGuard: LaunchLoginGuard? = null

    /** MCP hub step 6: every server the hub runs for this launch, handed to Claude Code with --mcp-config under its
     *  own name, plus --plugin-dir-no-mcp where a plugin's servers all run there. A launch through the wrapped
     *  `claude` falls back to the V4-445 file of hosted user servers. Assigned by the daemon before routes serve
     *  requests; null runs every launch over its own servers. */
    public var hubLaunch: McpHubLaunch? = null

    /** The MCP servers the daemon answers itself (a mode whose kind is mcp), added to every launch whatever the hub
     *  carries of the person's own. Assigned by the daemon before routes serve requests; null adds none. */
    public var ownMcp: OwnMcpLaunch? = null

    /** The control plane binds a successful local recipe to the shim PID that execs the harness. */
    public fun interface PeerLaunch {
        public suspend fun attach(caller: LaunchCaller, recipe: LaunchRecipe): LaunchRecipe
    }

    public var peerLaunch: PeerLaunch? = null

    /** Where the daemon keeps the launch's mod settings, assigned before serving recipes. */
    public var launchNotices: splice.launch.LaunchNotices? = null

    /** LAN share: the commands another machine serves, launched when a name no head here carries is asked for.
     *  Assigned by the daemon before routes serve requests; null launches heads only. */
    public var remotes: RemoteCommands? = null

    /** gpt-speed: the speeds each head's launch offers as /fast and /ultrafast. Assigned by the daemon before routes serve
     *  requests; null offers none, and every head keeps Claude Code's own /fast. */
    public var speeds: LaunchSpeeds? = null

    /** door-default: the `claude` command's launch while it is on the door. Assigned by the daemon before routes serve
     *  requests; null launches `claude` on its head, as before. */
    public var door: ClaudeDoor? = null

    /** head-catalog: what a head's command changes in its launch to start on the Splice gateway. */
    private val onGateway = GatewayLaunch()

    /** What a launch of `claude` on the [door] runs. */
    internal val onDoor = DoorRecipe(
        wrap,
        wrapState,
        claudeBinary,
        envReader,
        LaunchNoticesNow { launchNotices },
    ) { ownMcp }

    /** What a launch merges with the environment it inherits: NO_PROXY and the custom headers. */
    private val ambient = LaunchAmbient(envReader)

    /** How the head's effort reaches the Claude Code this launch starts. */
    private val effort = LaunchEffort()

    /** V4-183 and V4-115: what a launch resumes, and what the operator is told about it. */
    private val resume = LaunchResume(resumeAcrossHeads, moving)

    /** The resume hook shares the launch's rewrite and selected original-store root. */
    public val transcriptRewriter: TranscriptModelRewrite
        get() {
            return resumeAcrossHeads.rewriter
        }

    /** Materialize the head's config + build the exec recipe. Safe by default: the flag is added
     *  ONLY when [dangerouslySkipPermissions] is true, and doing so returns a non-null warning.
     *
     *  [keyPresentNow] (DR-81) is the LAUNCH-time key-presence read (LaunchHead.keyPresence).
     *  The spec is assembled once at boot and carries the capture capability ungated; whether the
     *  paste-your-key hook and advertiser materialize is decided here, per launch — `splice key
     *  set` promises live pickup, and a present key must disarm both (an accidental paste would
     *  silently OVERWRITE the working credential — review of #75). */
    public fun launchOutcome(
        spec: LaunchSpec,
        extraArgs: List<String>,
        dangerouslySkipPermissions: Boolean,
        keyPresentNow: Boolean = true,
        caller: LaunchCaller = LaunchCaller(),
    ): LaunchOutcome {
        // ONE read, acted on here: argv[0] is chosen from it below, and a record that cannot name the real binary
        // while the shim stands in for `claude` ends the launch before anything is materialized.
        val chosen = wrapState.claude()
        if (chosen is ClaudeToRun.Refused) return LaunchOutcome.Refused(chosen.reason)
        val claude = (chosen as? ClaudeToRun.Wrapped)?.path ?: claudeBinary
        val wrapped = caller.wrapped
        val captured = if (keyPresentNow) withoutTokenCapture(spec) else spec
        // head-catalog: a session on the gateway holds the gateway's key alone, as `claude` does: the head folder's
        // helper of its own turn key, kept there for the sessions launched before (GatewayLaunch.folderHelpers), is set
        // aside by the session's overlay (Claude Code 2.1.329 warns "Both ANTHROPIC_AUTH_TOKEN and apiKeyHelper set")
        val keyed = onGateway.keyed(captured, caller.gateway)
        val tree = wrapped?.let { keyed.copy(trees = keyed.trees.copy(own = it.configDir)) } ?: keyed
        // Telemetry the command's managed settings set is theirs, and splice then sends none (server mode, F8).
        val effective = withManagedTelemetry(tree)
        val slots = onGateway.slots(effective, caller.gateway)
        // V4-358: every choice above is made on the rows' own ids; what the client is HANDED is spelled here.
        val held = effort.given(effective.heldByClient(), extraArgs)
        // head-catalog: no head authors a roster, a picker cache or a saved model choice; the gateway's list is the one.
        val materialize = MaterializeSpec(
            configDir = effective.trees.own,
            policy = effective.policy,
            statuslineCommand = effective.gateway.statuslineCommand,
            signIn = MaterializeSignIn(
                loginCommand = effective.signIn.loginCommand,
                signsInOn = effective.signIn.signsInOn,
                tokenCapture = effective.signIn.tokenCapture,
                advertiseKeySetup = effective.signIn.advertiseKeySetup,
                loginOutcomeFile = effective.signIn.loginOutcomeFile,
                headKey = effective.signIn.headKey,
            ),
        )
        // V4-283: the folder-trust records the operator granted for this cwd, in any head, are carried in, and for
        // the resume of a move in flight, the folder it is moving out of: the one way home is ever trusted.
        val trust = caller.absoluteCwd()
            ?.let { TrustedLaunch(it, effective.trees.siblings, resume.movingFolder(extraArgs)) }
        // V4-445: a wrapped launch materializes NOTHING: it runs over the operator's own ~/.claude and ~/.claude.json,
        // which stay as they are, and carries the head's settings as a --settings overlay below.
        val speed = materializeLaunch(materialize, trust, onGateway.folderHelpers(effective, captured), wrapped)
        // V4-276: a launch never writes a head's .credentials.json. V4-129 copied the selected stored
        // login over it here, and Claude Code's refresh tokens rotate, so every launch put back a
        // superseded token and upstream revoked the login (V4-237, V4-250). The live login is
        // Claude Code's own; `splice login <claude-head> --label` (ClaudeLogins.login) is the one
        // place a stored login is put back, with the head idle and the live one saved first.
        // V4-115 AFTER the materialize, never before: the materializer is what guarantees
        // <configDir>/projects is a REAL head-owned directory (ProjectsLink un-links one an earlier
        // launch pointed elsewhere). Copying first would write through the very link this row removes.
        // V4-183 BEFORE the adoption: a bounded -c becomes a named resume, and a named resume is what
        // adoptResume judges. Read from the head's own index, a wrapped launch's too (round 6 of #247): the hook
        // route records every session of the head there, and nothing records one under ~/.claude.
        val bounded = resume.bounded(keyed.trees.own, extraArgs, caller.cwd)
        // a -r NAME of a session another command wrote is the id it stands for, so it is adopted as that id is
        val asked = resume.named(held, bounded.args, caller.cwd)
        val adoption = resume.adopt(held, asked, caller.cwd)
        // V4-445: Claude Code reads ~/.claude.json only while CLAUDE_CONFIG_DIR is unset. Set to the vanilla dir it
        // reads ~/.claude/.claude.json, a file that holds none of the operator's mcpServers, projects or account.
        val environment = speed.environment(launchEnvironment(spec, held, slots, caller))
        val hub = hubArgs(caller, wrapped, effective, claude, extraArgs)
        val argv = buildList {
            // V4-129: the real absolute path when `claude` on PATH is currently the wrap shim itself
            // (see [wrapState]'s KDoc) — bare [claudeBinary] otherwise, byte-identical to every launch
            // before this row.
            add(claude)
            if (dangerouslySkipPermissions) add("--dangerously-skip-permissions")
            // a launch on macOS carries splice's mark in its --settings, the one sign of it a hardened claude's
            // arguments keep there (LaunchMarker); elsewhere the arguments are as they were
            val overlay = overlayOf(caller, held, keyed) ?: onGateway.overlay(captured, caller.gateway)
            addAll(LaunchMarker.settingsArgs(overlay))
            addAll(hub.args + effort.args(held)) // the effort after the hub's: a relaunch reads it as its own
            // NB: no --model — the active model is ANTHROPIC_MODEL + settings.json, so the /model
            // picker (populated by the materialized bare-id roster) can freely switch. Forcing it locked the row.
            // V4-169: a session another head wrote goes on as the copy the client resumes.
            addAll(resume.resumingAs(asked, adoption))
        }
        val warnings = listOf(resume.warning(adoption), bounded.warning, hub.warning)
        val warning = warningOf(spec, dangerouslySkipPermissions, warnings, caller)
        onGateway.record(caller.launch, spec.signIn.headKey, caller.gateway)
        return LaunchOutcome.Ready(environment.recipe(argv, warning, caller, launchNotices))
    }

    /** The launch's warnings in one line, and on a line of its own after them the notice of a custom head:
     *  door-default, decision 7 as head-catalog changed it, every custom head's launch says custom heads are deprecated
     *  and will be removed in a coming release, and what to type instead, `claude` on its head too (a head itself);
     *  `claude` on the Splice gateway runs no head and says nothing of it (DoorRecipe). */
    private fun warningOf(spec: LaunchSpec, skip: Boolean, warnings: List<String?>, caller: LaunchCaller): String? {
        val notice = caller.command?.let { HeadsDeprecated().said(it, spec, caller.gateway, door) }
        return listOfNotNull(launchWarning(spec, skip, warnings), notice).takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** A wrapped launch's --settings overlay: the status line, splice's launch mark on macOS, the key helper of a head
     *  that checks the turn key (Oct 26), and [head]'s startup hook, so the session is recorded as the head's (round 6
     *  of #247). No model list (head-catalog). Null for a launch that materialized its own settings. */
    private fun overlayOf(caller: LaunchCaller, held: LaunchSpec, head: LaunchSpec): String? {
        val wrapped = caller.wrapped ?: return null
        val hooks = materializer.startupHook(head.trees.own, head.signIn.headKey)
        return wrapped.settingsOverlay(held.gateway.statuslineCommand, LaunchMarker.here, held.helpers, hooks)
    }

    /** MCP hub step 6: what the hub adds to this launch. A wrapped launch reads the user's own config home and falls
     *  back to the V4-445 file; a head's launch reads the head's home. [asked], the person's words, decide whether the
     *  daemon's own servers join them. */
    private fun hubArgs(
        caller: LaunchCaller,
        wrapped: WrappedLaunch?,
        effective: LaunchSpec,
        claude: String,
        asked: List<String>,
    ): HubLaunchArgs {
        val launch = HubLaunchFor(
            configDir = wrapped?.configDir ?: effective.trees.own,
            folder = caller.absoluteCwd(),
            launch = caller.launch,
            claude = Path.of(claude),
            overlayHead = effective.signIn.headKey.takeIf { wrapped != null },
        )
        val carried = hubLaunch?.args(launch) ?: HubLaunchArgs(emptyList())
        // a wrapped launch is the door's; a head's own launch is its head's, in the words a mode's manifest uses
        val asking = if (wrapped == null) OWN_MCP_CALLER_HEAD else OWN_MCP_CALLER_DOOR
        val head = effective.signIn.headKey
        return ownMcp?.let { carried.copy(args = it.added(carried.args, head, asking, asked)) } ?: carried
    }

    /** Writes the head's config unless the launch is [wrapped], which materializes nothing. gpt-speed: a head signed in
     *  with ChatGPT also offers its pinned model's tiers as /fast and /ultrafast, which this answers; a wrapped launch
     *  offers none. */
    private fun materializeLaunch(
        spec: MaterializeSpec,
        trust: TrustedLaunch?,
        helpers: SessionHelpers,
        wrapped: WrappedLaunch?,
    ): SpeedOffer {
        val tiers = speeds?.takeIf { wrapped == null }?.offered(spec.signIn.headKey)
        if (wrapped == null) materializer.materialize(spec, trust, helpers, tiers)
        return SpeedOffer(tiers)
    }

    /** [spec] with no telemetry of splice's where its config tree's managed settings set telemetry of their own. */
    private fun withManagedTelemetry(spec: LaunchSpec): LaunchSpec {
        val server = spec.teamServer?.takeIf { it.telemetry != null } ?: return spec
        val own = managed.setsTelemetry(spec.trees.own)
        return if (own) spec.copy(teamServer = server.copy(telemetry = null)) else spec
    }

    /** Everything the operator must be told about this launch, in one sentence. A cross-head adoption
     *  is ANNOUNCED (it is an explicit act on one named session, not a shared tree); a resume that
     *  found nothing is refused in words before Claude Code refuses the id itself. Every id these
     *  sentences carry has passed ResumeAcrossHeads' session-id shape check, so none of them can
     *  forge a line in the shim's stderr. */
    private fun launchWarning(
        spec: LaunchSpec,
        dangerouslySkipPermissions: Boolean,
        others: List<String?>,
    ): String? {
        val danger = "dangerouslySkipPermissions engaged for ${spec.trees.own}: $DANGER"
            .takeIf { dangerouslySkipPermissions }
        return (listOf(danger) + others).filterNotNull().takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /** Vars a launched head must SCRUB from the inherited environment: app/src/main/dist/bin/splice-launch execs
     *  `env` WITHOUT -i, so a head launched from inside another head's session inherits the OUTER
     *  recipe (the same mechanism that let the mgmt key reach a native head — DR-30). Three
     *  classes: (1) a foreign head strips the client's Anthropic session; a native head keeps it —
     *  those variables ARE the credential being forwarded. (2) Gateway model discovery, retired:
     *  an ambient =1 would re-add the wrapped /v1/models spelling this recipe keeps out of the
     *  picker. (3) Every alias-tier triplet this head does NOT emit — an explicit-slots head that
     *  omits a tier must not let the outer head's value leak through and point that tier at a
     *  model this head cannot serve (codex redo verdict, 2026-08-30). */
    private fun launchEnvironment(
        spec: LaunchSpec,
        held: LaunchSpec,
        slots: List<Pair<String, String>>,
        caller: LaunchCaller,
    ): LaunchEnvironment {
        val wrapped = caller.wrapped
        val gateway = caller.gateway
        val auth = if (spec.gateway.forwardClientAuth) {
            emptyList()
        } else {
            listOf(
                "ANTHROPIC_API_KEY",
                "CLAUDE_CODE_OAUTH_TOKEN",
                "CLAUDE_CODE_OAUTH_REFRESH_TOKEN",
            ) + helperShadows(spec)
        }
        val emitted = slots.map { it.first }.toSet()
        val absentTiers = ClaudeTiers.names.filterNot { it in emitted }.flatMap(ClaudeTiers::variables)
        val dirs = listOf(spec.trees.own) + spec.trees.siblings
        val config = wrapped?.configRootUnsets(caller.inheritedConfigDir, dirs).orEmpty()
        // V4-449: what this head no longer plants must not leak in from an outer head's session either.
        val own = { tiers: List<Pair<String, String>> -> buildEnv(held, tiers, caller.ambient) }
        val planted = (gateway?.let { onGateway.env(own(emptyList()), held, it) } ?: own(slots)) +
            effort.env(held)
        val clientModels = if (spec.gateway.clientModels) {
            LaunchModelEnvironment.inherited(planted)
        } else {
            emptyList()
        }
        val env = if (wrapped == null) planted else planted - CONFIG_DIR_ENV
        // Claude Code's provider switches, cleared from every launch: a shell inside a Code tab session carries the
        // desktop door's, and a session that inherits any of them goes past its head. (4) the word that the address is
        // Anthropic's own, which only a head that reaches Anthropic plants: a translated head launched from inside a
        // Claude session must not inherit it. On the gateway: every way past it and every list but its own.
        val unset = auth + GATEWAY_DISCOVERY + absentTiers + clientModels + config + providerSwitches +
            listOfNotNull(FIRST_PARTY_ENV.takeUnless { spec.gateway.reachesAnthropic }) +
            LaunchTelemetry.cleared(held) + gateway?.let { onGateway.unset }.orEmpty()
        return LaunchEnvironment(env, unset.distinct() - env.keys)
    }

    /** Oct 26 (key rotation): Claude Code prefers an ANTHROPIC_AUTH_TOKEN over the key helper and then never runs the
     *  helper again, not even after a 401 (read in the 2.1.310 binary), so one an outer session would pass down is
     *  scrubbed from a launch whose turn key comes from the helper. */
    private fun helperShadows(spec: LaunchSpec): List<String> =
        if (spec.gateway.keyHelper != null) listOf("ANTHROPIC_AUTH_TOKEN") else emptyList()

    /** What [spec]'s launch sets with its tiers [slots], beside [callerAmbient] (LaunchCallerAmbient). */
    private fun buildEnv(
        spec: LaunchSpec,
        slots: List<Pair<String, String>>,
        callerAmbient: LaunchCallerAmbient,
    ): Map<String, String> {
        val noHold = callerAmbient.noHold == true
        val wallMs = LaunchClientTimers.wallMs(spec.gateway.apiTimeoutMs, noHold)
        return buildMap {
            // LAN share: a command another machine serves goes to that machine's address, never through this one.
            put("ANTHROPIC_BASE_URL", spec.gateway.remote ?: "http://127.0.0.1:${spec.gateway.port}")
            // AUTH_TOKEN (bearer), NOT API_KEY — a bearer avoids Claude Code's custom-api-key
            // approval flow. The head validates this per-install credential before any quota-
            // consuming work. Only a PLANTED bearer is put here (a LAN share's word): a gateway head's
            // session reads its turn key through the apiKeyHelper its settings carry, which Claude Code
            // runs again after a 401, so a rotated key never strands it (Oct 26). A native-auth head plants
            // NOTHING: the client's own credential must reach the head untouched, and this would override it.
            spec.gateway.plantedToken?.let { put("ANTHROPIC_AUTH_TOKEN", it) }
            put(CONFIG_DIR_ENV, spec.trees.own.toString())
            // NO gateway model discovery: the picker reads the materialized roster (settings.json
            // availableModels + .claude.json additionalModelOptionsCache) — see the header for why
            // the wrapped /v1/models spelling must never reach the picker.
            putAll(LaunchModelEnvironment.models(spec, slots))
            putAll(LaunchModelEnvironment.windows(spec))
            putAll(LaunchModelEnvironment.compactPercent)
            // The default only: splice-launch keeps a MAX_THINKING_TOKENS the caller already set, so a headless caller
            // that bounds its own thinking keeps that bound.
            put("MAX_THINKING_TOKENS", "128000")
            // The client's own timers and retry limits, so the head's wall decides every turn (LaunchClientTimers).
            putAll(LaunchClientTimers.of(spec.gateway.apiTimeoutMs, noHold))
            // Server mode (F8): Claude Code's own telemetry goes to its server's relay, when the server has one.
            putAll(LaunchTelemetry.env(spec))
            // Failure-handling slice 5: the same timeout, told to the head on every request, so a turn held for a reset
            // fits the session that sent it (a session launched before this reads as the 960 s every session had).
            // The shim adds the caller's own custom headers before these (LaunchAmbient).
            putAll(ambient.customHeaders(wallMs, ownHeaders(spec, noHold), stripNoHold = callerAmbient.noHold != null))
            putAll(ambient.noProxy(spec.gateway.remote, callerAmbient.noProxy))
            // Hide Claude Code's built-in Anthropic-account commands: in a gateway head, auth is the
            // proxy bearer above, so /login (a local-jsx command hardwired to platform.claude.com —
            // no hook or base-url override can reach it) and /logout are dead doors. These are the
            // CLI's own boolean env flags (Pe.bool over process.env), so the commands never register.
            //
            // A native-auth head keeps BOTH: its upstream really is Anthropic, so /login is a live
            // door and the only one that can heal a rejected credential — splice runs no sign-in
            // flow of its own for this head precisely because the client's still works.
            if (!spec.gateway.forwardClientAuth) {
                put("DISABLE_LOGIN_COMMAND", "1")
                put("DISABLE_LOGOUT_COMMAND", "1")
            }
            // Auto mode asks Anthropic's server to check each action. A translated head is not Anthropic, so that
            // check can never come back, and Claude Code stops the first checked action to tell the user their
            // gateway is incompatible. Say up front that the checks are the client's own. A Claude head, here or on
            // another machine (LAN share), reaches Anthropic and keeps the server's checks.
            if (!spec.gateway.reachesAnthropic) put("CLAUDE_CODE_AUTO_MODE_SERVER", "0")
            // The same head is Anthropic's API at splice's address, and the base URL's own companion variable says so.
            // Without it Claude Code takes the address for a gateway's and sends its small requests (the usage check
            // at startup, titles, summaries) on the session's main model instead of its small one. Anthropic answers
            // that bare usage check on most models with a 429, which Claude Code shows as a limit reached (Nov 17,
            // 2026: sessions started without the user settings that happened to carry the variable on that machine).
            if (spec.gateway.reachesAnthropic) put(FIRST_PARTY_ENV, "1")
            put("SPLICE", "1")
        }
    }

    /** splice's headers for [spec]'s session besides its client wall (LaunchAmbient.customHeaders): its window, on a
     *  head here that forwards the client's login the launch's proof, a team server's word that this is splice, and
     *  [NO_HOLD_HEADER] when the caller opted out of holds. */
    private fun ownHeaders(spec: LaunchSpec, noHold: Boolean): List<String> {
        // Server mode (F9): a team server's command names this laptop's splice, so an older one is told to upgrade.
        val client = listOfNotNull(spec.teamServer?.let { "$SPLICE_CLIENT_HEADER: $GATEWAY_VERSION" })
        val optOut = if (noHold) listOf("$NO_HOLD_HEADER: 1") else emptyList()
        return LaunchModelEnvironment.windowHeader(spec) + spec.gateway.proofLines + client + optOut
    }

    private fun withoutTokenCapture(spec: LaunchSpec) =
        spec.copy(signIn = spec.signIn.copy(tokenCapture = null, advertiseKeySetup = false))
}

/** The variable that moves Claude Code's whole config root, and its global .claude.json with it. */
private const val CONFIG_DIR_ENV = "CLAUDE_CONFIG_DIR"

// why: who a launch is for, in the words a mode's manifest uses for its callers (modes/README.md, `[callers] off`).
internal const val OWN_MCP_CALLER_DOOR: String = "door"
private const val OWN_MCP_CALLER_HEAD = "head"
