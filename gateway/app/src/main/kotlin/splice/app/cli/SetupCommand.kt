// NEW: `splice setup` — guided flow on the prompt toolkit (cli-wizard CW-7, CW-10).
// Confirm is load-bearing: declining writes nothing. Non-TTY takes every default and
// writes the same topology the pre-campaign command wrote. Extra heads are ticked from
// AddProfiles and installed by calling AddCommand; the wizard never writes those tables.
package splice.app.cli

import splice.app.TopologyLoader
import splice.app.cli.prompt.SelectOption
import splice.app.cli.prompt.SelectOutcome
import splice.app.cli.prompt.WizardCancelled
import splice.app.cli.setup.CLAUDE_PROFILE
import splice.app.cli.setup.ClaudeWrap
import splice.app.cli.setup.DaemonClaudeWrap
import splice.app.cli.setup.SetupClaudeLane
import splice.app.cli.setup.SetupPrompts
import splice.app.cli.setup.SetupSignIn
import splice.core.topology.AuthKindRegistry
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

/** The `setup` verb. Production constructs with defaults so Command.Setup does not change. */
internal class SetupCommand(
    private val installCommand: InstallCommand = InstallCommand(),
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
    private val addProfile: ProfileAdd = ProfileAdd { name ->
        AddCommand(restart = DaemonRestart { true }).add(listOf(name, "--yes"))
    },
    private val restart: DaemonRestart = DaemonRestart { RestartCommand().restart() },
    /** V4-175: the wrap call. Not a prompt — it CHANGES THE MACHINE, which is the line SetupPrompts
     *  draws, so it stays here beside install, add and restart. */
    private val wrapClaude: ClaudeWrap = DaemonClaudeWrap(),
) {
    private val frame = prompts.frame

    /** The post-install OAuth tail, in splice.app.cli.setup since V4-156 (concentration). */
    private val signIn = SetupSignIn(loginHead)

    internal suspend fun setup(): Boolean = try {
        runWizard()
    } catch (_: WizardCancelled) {
        true
    }

    private suspend fun runWizard(): Boolean {
        frame.intro("splice setup")
        val facts = detect()
        printDetected(facts)
        val options = startOptions(facts)
        val start = chosenStart(prompts.choose(options, initialIndex(facts, options)))
        val path = TopologyLoader.configPath(env)
        val bin = InstallLayout().localBin(env)
        val lanes = SetupClaudeLane(prompts.pickLane, wrapClaude)
        val picker = SetupHeads(profiles, prompts.pickHeads, addProfile, restart, prompts.hasConsole, env, frame)
        val heads = picker.offer(facts, path)
        val lane = lanes.ask(heads)
        frame.note("Summary", summaryLines(start, path, bin, heads, lanes.summaryLine(lane)))
        if (!frame.confirm("Install now?", true)) frame.cancel("not installing")
        prompts.spinner.start("Installing")
        val result = Cancellables.runCatchingBestEffort { runInstall() }
        val installed = result.fold(onSuccess = { it }, onFailure = { false })
        prompts.spinner.stop(if (installed) "Installed wrappers" else "Install failed")
        result.exceptionOrNull()?.let { throw it }
        if (!installed) return false
        lanes.apply(lane, picker.addAll(heads))?.let { println(it) }
        val topology = TopologyLoader.loadOrMaterialize(path)
        val ok = signIn.signInPendingHeads(topology)
        signIn.printNextSteps(topology)
        frame.outro("Toolkit ready!")
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

    private fun chosenStart(picked: SelectOutcome<SetupStart>): SetupStart = when (picked) {
        is SelectOutcome.Chosen -> picked.value
        SelectOutcome.Cancelled -> frame.cancel("cancelled")
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
            "Starter topology → $path"
        }
        val wrappers = "Wrapper commands under $bin"
        val extra = when (start) {
            is SetupStart.OAuth ->
                listOf("Starter is still OpenRouter; add that head with: splice add ${start.kind}")
            else -> emptyList()
        }
        val adding = if (heads.isEmpty()) {
            emptyList()
        } else {
            listOf("Heads to add: ${heads.joinToString(", ")}")
        }
        val keys = if (heads.any { name -> profiles.find(name)?.authKind == API_KEY_KIND }) {
            listOf("API keys written by splice add (keys.toml, 0600)")
        } else {
            emptyList()
        }
        // The lane line only when the Claude head is actually being added: a summary that answered
        // a question nobody was asked is noise the operator has to parse past.
        val lane = if (CLAUDE_PROFILE in heads) listOf(claudeLane) else emptyList()
        return listOf(starter, wrappers) + extra + adding + lane + keys
    }

    private fun runInstall(): Boolean {
        installCommand.init()
        if (!installCommand.install("--all")) return false
        installCommand.installSelf()
        return true
    }
}
