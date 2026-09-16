// NEW: `splice setup` — guided flow on the prompt toolkit (cli-wizard CW-7, CW-10).
// Confirm is load-bearing: declining writes nothing. Non-TTY takes every default and
// writes the same topology the pre-campaign command wrote. Extra heads are ticked from
// AddProfiles and installed by calling AddCommand; the wizard never writes those tables.
package splice.app.cli

import splice.app.TopologyLoader
import splice.app.cli.prompt.KeyReader
import splice.app.cli.prompt.MultiSelectPrompt
import splice.app.cli.prompt.SelectOption
import splice.app.cli.prompt.SelectOutcome
import splice.app.cli.prompt.SelectPrompt
import splice.app.cli.prompt.Spinner
import splice.app.cli.prompt.TerminalMode
import splice.app.cli.prompt.WizardCancelled
import splice.app.cli.prompt.WizardFrame
import splice.core.topology.AuthKindRegistry
import splice.core.topology.Topology
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

/** The `setup` verb. Production constructs with defaults so Command.Setup does not change. */
internal class SetupCommand(
    private val installCommand: InstallCommand = InstallCommand(),
    private val loginHead: HeadSignIn = HeadSignIn { key -> LoginCommand().login(key) },
    private val frame: WizardFrame = WizardFrame(),
    private val detect: SetupProbe = SetupProbe {
        SetupDetection(
            EnvReader(System::getenv),
            CredentialPresenceProbe { path -> AdminSupport.authPresent(path) },
            DaemonUpProbe { port -> AdminSupport.daemonUp(port) },
        ).detect()
    },
    private val choose: StartChoice = StartChoice { options, index ->
        SelectPrompt(KeyReader(System.`in`), TerminalMode(), System.out).ask(
            "Starting point",
            options,
            index,
        )
    },
    private val spinner: Spinner = Spinner(),
    private val env: EnvReader = EnvReader(System::getenv),
    private val profiles: AddProfiles = AddProfiles(),
    private val pickHeads: HeadPicker = HeadPicker { options, initial ->
        MultiSelectPrompt(KeyReader(System.`in`), TerminalMode(), System.out).ask(
            "Heads to add",
            options,
            initial,
            minimum = 0,
        )
    },
    private val addProfile: ProfileAdd = ProfileAdd { name ->
        AddCommand(restart = DaemonRestart { true }).add(listOf(name, "--yes"))
    },
    private val restart: DaemonRestart = DaemonRestart { RestartCommand().restart() },
    private val hasConsole: ConsolePresence = ConsolePresence { System.console() != null },
) {

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
        val start = chosenStart(choose(options, initialIndex(facts, options)))
        val path = TopologyLoader.configPath(env)
        val bin = InstallLayout().localBin(env)
        val picker = SetupHeads(profiles, pickHeads, addProfile, restart, hasConsole, env, frame)
        val heads = picker.offer(facts, path)
        frame.note("Summary", summaryLines(start, path, bin, heads))
        if (!frame.confirm("Install now?", true)) frame.cancel("not installing")
        spinner.start("Installing")
        val result = runCatching { runInstall() }
        val installed = result.getOrNull() == true
        spinner.stop(if (installed) "Installed wrappers" else "Install failed")
        result.exceptionOrNull()?.let { throw it }
        if (!installed) return false
        picker.addAll(heads)
        val topology = TopologyLoader.loadOrMaterialize(path)
        val ok = signInPendingHeads(pendingOAuthHeads(topology))
        printNextSteps(topology)
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

    private fun summaryLines(start: SetupStart, path: Path, bin: Path, heads: List<String>): List<String> {
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
        return listOf(starter, wrappers) + extra + adding + keys
    }

    private fun runInstall(): Boolean {
        installCommand.init()
        if (!installCommand.install("--all")) return false
        installCommand.installSelf()
        return true
    }

    private fun pendingOAuthHeads(topology: Topology): List<PendingOAuthHead> =
        topology.heads.entries.mapNotNull { (key, head) ->
            val provider = topology.providers[head.provider] ?: return@mapNotNull null
            if (AuthKindRegistry.isOAuth(provider.auth.kind) &&
                !authPresent(provider.auth.file, provider.auth.kind)
            ) {
                PendingOAuthHead(key, head.claude.command ?: key)
            } else {
                null
            }
        }

    private suspend fun signInPendingHeads(pending: List<PendingOAuthHead>): Boolean {
        if (pending.isEmpty()) {
            println("$GREEN✓$RESET wrapper installed. Set OPENROUTER_API_KEY before launching.")
            return true
        }
        println(
            "$DIM  Subscription heads reuse each vendor CLI's public OAuth client identity, signed in " +
                "separately for splice (its own credential file, any account) — " +
                "unofficial; use at your own risk.$RESET",
        )
        var ok = true
        for ((key, command) in pending) {
            if (AdminSupport.confirm("Sign in to $CYAN$command$RESET now?", default = true)) {
                if (!loginHead(key)) ok = false
            } else {
                println("  ${DIM}skipped — sign in later with: $command login$RESET")
            }
        }
        return ok
    }

    private fun authPresent(file: String?, kind: String): Boolean {
        val path = file ?: AuthKindRegistry.defaultAuthFileFor(kind) ?: return false
        return AdminSupport.authPresent(path)
    }

    private fun printNextSteps(topology: Topology) {
        println()
        println("${BOLD}You're set.$RESET")
        val commands = topology.heads.map { (k, h) -> h.claude.command ?: k }
        println("  Launch      ${commands.joinToString("$DIM · $RESET") { "$CYAN$it$RESET" }}")
        println("  Dashboard   ${CYAN}splice dashboard$RESET")
        println("  Status      ${CYAN}splice status$RESET")
        println("  Checkup     ${CYAN}splice doctor$RESET $DIM— anything wrong prints its fix$RESET")
    }
}
