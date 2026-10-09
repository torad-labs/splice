// NEW: `splice setup` — guided flow on the prompt toolkit (cli-wizard CW-7, CW-10).
// Confirm is load-bearing: declining writes nothing. Non-TTY takes every default and
// writes the same topology the pre-campaign command wrote. Extra heads are ticked from
// AddProfiles and installed by calling AddCommand; the wizard never writes those tables.
package splice.app.cli.setup

import splice.app.AddWiring
import splice.app.InstallWiring
import splice.app.LifecycleWiring
import splice.app.cli.AdminSupport
import splice.app.cli.auth.LoginCommand
import splice.configuration.add.AddProfiles
import splice.configuration.add.DaemonRestart
import splice.configuration.add.DaemonUpProbe
import splice.core.topology.AuthKindRegistry
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.launch.install.InstallLayout
import splice.launch.install.InstallResult
import splice.terminal.SelectOption
import splice.terminal.SelectOutcome
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

/** The steps of `setup` that change the machine, beside install: the profile add, the daemon restart and the
 *  Claude wrap. Not prompts, which is the line SetupPrompts draws. [wrap] reads through [env], not a fresh
 *  `EnvReader(System::getenv)`: DaemonClaudeWrap reads the mgmt-key and resolves the control port through it, so a
 *  default that ignored the hermetic environment was one preselection change away from a test wrapping the
 *  developer's own ~/.claude (V4-177 review). */
internal class SetupEffects(
    env: EnvReader,
    private val add: ProfileAdd = ProfileAdd { name ->
        AddWiring.add(restart = DaemonRestart { true }).add(listOf(name, "--yes"), EnvReader(System::getenv))
    },
    val restart: DaemonRestart = DaemonRestart { LifecycleWiring.restart() },
    private val wrap: ClaudeWrap = DaemonClaudeWrap(env),
) {
    fun heads(profiles: AddProfiles, prompts: SetupPrompts, env: EnvReader): SetupHeads =
        SetupHeads(profiles, prompts.pickHeads, add, restart, prompts.hasConsole, env, prompts.frame)

    fun lanes(prompts: SetupPrompts): SetupClaudeLane = SetupClaudeLane(prompts.pickLane, wrap)
}

/** The `setup` verb. Production constructs with defaults so Command.Setup does not change. */
internal class SetupCommand(
    loginHead: HeadSignIn = HeadSignIn { key -> LoginCommand().login(key) },
    /** V4-176: the six terminal seams as one collaborator — see SetupPrompts for why they were
     *  always one. A test replaces the bundle; production takes its defaults. */
    private val prompts: SetupPrompts = SetupPrompts(),
    private val detect: SetupProbe = SetupProbe {
        SetupDetection(
            EnvReader(System::getenv),
            CredentialPresenceProbe { path -> AdminSupport.authPresent(path) },
            DaemonUpProbe { port -> AdminSupport.daemonUp(port) },
        ).detect()
    },
    private val env: EnvReader = EnvReader(System::getenv),
    private val profiles: AddProfiles = AddProfiles(),
    private val effects: SetupEffects = SetupEffects(env),
    /** The optional local-model step (rig), on this class's env and restart. */
    private val localModel: SetupLocalModel = SetupLocalModel(prompts, env, effects.restart),
) {
    private val frame = prompts.frame

    /** The post-install OAuth tail, in splice.app.cli.setup since V4-156 (concentration). */
    private val signIn = SetupSignIn(loginHead, env)

    internal suspend fun setup(): Boolean = runWizard()

    private suspend fun runWizard(): Boolean = when (val answer = ask()) {
        is WizardAnswer.Cancelled -> frame.cancel(answer.reason)
        is WizardAnswer.Plan -> install(answer)
    }

    /** The questions, up to the operator's go: the plan to install, or the reason the wizard ends without it. */
    private suspend fun ask(): WizardAnswer {
        frame.intro("splice setup")
        val facts = detect()
        printDetected(facts)
        val options = startOptions(facts)
        val picked = prompts.choose(options, initialIndex(facts, options))
        val start = chosenStart(picked) ?: return WizardAnswer.Cancelled("cancelled")
        val path = TopologyLoader.configPath(env)
        val bin = InstallLayout().localBin(env)
        val lanes = effects.lanes(prompts)
        val picker = effects.heads(profiles, prompts, env)
        val heads = picker.offer(facts, path)
        val lane = lanes.ask(heads)
        val local = localModel.offer(path)
        val summary = summaryLines(start, path, bin, heads, lanes.summaryLine(lane)) + localModel.summary(local)
        frame.note("Summary", summary)
        if (!frame.confirm("Install now?", true)) return WizardAnswer.Cancelled("not installing")
        return WizardAnswer.Plan(path, lanes, lane, picker, heads, local)
    }

    private suspend fun install(plan: WizardAnswer.Plan): Boolean {
        prompts.spinner.start("Installing")
        val result = Cancellables.runCatchingBestEffort { runInstall() }
        val installed = result.fold(onSuccess = { it is InstallResult.Linked }, onFailure = { false })
        if (installed) prompts.spinner.stop("Installed wrappers") else prompts.spinner.fail("Install failed")
        // A refusal prints its sentence on one line, as the install verb does, after the spinner has stopped.
        result.getOrNull()?.let { if (it is InstallResult.Refused) System.err.println("splice: ${it.sentence}") }
        result.exceptionOrNull()?.let { throw it }
        if (!installed) return false
        plan.lanes.apply(plan.lane, plan.picker.addAll(plan.heads))?.let { println(it) }
        localModel.install(plan.local)
        val topology = TopologyLoader.loadOrMaterialize(plan.path)
        val ok = signIn.signInPendingHeads(topology)
        signIn.printNextSteps(topology)
        // The ONE completion line on the last screen — printNextSteps deliberately has none. "You're
        // set." rather than "Toolkit ready!": splice is not called a toolkit anywhere else.
        frame.outro(if (topology.heads.isEmpty()) "Not set up yet." else "You're set.")
        return ok
    }

    private fun printDetected(facts: SetupFacts) {
        val bits = mutableListOf<String>()
        if (facts.spliceOwned.isNotEmpty()) bits.add(facts.spliceOwned.sorted().joinToString(", "))
        if (facts.vendorCli.isNotEmpty()) bits.add(facts.vendorCli.sorted().joinToString(", "))
        if (facts.openRouterKey) bits.add("OPENROUTER_API_KEY")
        if (facts.daemonUp) bits.add("daemon")
        if (bits.isNotEmpty()) frame.step("Detected ${bits.joinToString(", ")}")
    }

    private fun startOptions(facts: SetupFacts): List<SelectOption<SetupStart>> {
        val options = mutableListOf<SelectOption<SetupStart>>()
        val keyCount = if (facts.openRouterKey) 1 else 0
        options.add(SelectOption(SetupStart.OpenRouter, "OpenRouter", "$keyCount keys"))
        for (kind in AuthKindRegistry.knownKinds().filter { it.isOAuth }) {
            val n = listOf(kind.wire in facts.spliceOwned, kind.wire in facts.vendorCli).count { it }
            options.add(SelectOption(SetupStart.OAuth(kind.wire), kind.signInLabel, "$n credentials"))
        }
        val daemons = if (facts.daemonUp) 1 else 0
        options.add(SelectOption(SetupStart.Existing, "Existing topology", "$daemons daemons"))
        return options
    }

    private fun initialIndex(facts: SetupFacts, options: List<SelectOption<SetupStart>>): Int {
        val i = options.indexOfFirst { it.value == facts.suggested }
        return if (i < 0) 0 else i
    }

    private fun chosenStart(picked: SelectOutcome<SetupStart>): SetupStart? = when (picked) {
        is SelectOutcome.Chosen -> picked.value
        SelectOutcome.Cancelled -> null
    }

    private fun summaryLines(
        start: SetupStart,
        path: Path,
        bin: Path,
        heads: List<String>,
        claudeLane: String,
    ): List<String> {
        val starter = if (Files.exists(path)) {
            "No starter will be written because one is already present"
        } else {
            "Starter topology (no plan) → $path"
        }
        val wrappers = "Wrapper commands under $bin"
        val suggested = when (start) {
            is SetupStart.OAuth -> profiles.catalog().firstOrNull { it.provider.authKind == start.kind }
            SetupStart.OpenRouter -> profiles.find("openrouter")
            SetupStart.Existing -> null
        }
        val extra = suggested?.takeUnless { it.name in heads }
            ?.let { listOf("Connect the chosen plan with: splice add ${it.name}") }.orEmpty()
        val adding = if (heads.isEmpty()) {
            emptyList()
        } else {
            listOf("Heads to add: ${heads.joinToString(", ")}")
        }
        val keys = if (heads.any { name -> profiles.find(name)?.provider?.authKind == API_KEY_KIND }) {
            listOf("API keys written by splice add (keys.toml, 0600)")
        } else {
            emptyList()
        }
        // The lane line only when the Claude head is actually being added: a summary that answered
        // a question nobody was asked is noise the operator has to parse past.
        val lane = if (CLAUDE_PROFILE in heads) listOf(claudeLane) else emptyList()
        return listOf(starter, wrappers) + extra + adding + lane + keys
    }

    private fun runInstall(): InstallResult {
        InstallWiring.init(env)
        val install = InstallWiring.command()
        val wrappers = install.install("--all", env)
        return if (wrappers is InstallResult.Linked) install.installSelf(env) else wrappers
    }
}

/** What the wizard's questions end in: a plan the operator said go to, or the reason it ends without installing. */
private sealed class WizardAnswer {
    class Cancelled(val reason: String) : WizardAnswer()

    class Plan(
        val path: Path,
        val lanes: SetupClaudeLane,
        val lane: ClaudeLane,
        val picker: SetupHeads,
        val heads: List<String>,
        val local: Boolean,
    ) : WizardAnswer()
}
