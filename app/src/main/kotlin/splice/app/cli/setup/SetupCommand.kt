// NEW: `splice setup` — guided flow on the prompt toolkit (cli-wizard CW-7, CW-10).
// Confirm is load-bearing: declining writes nothing. Non-TTY takes every default and
// writes the same topology the pre-campaign command wrote. Extra heads are ticked from
// AddProfiles and installed by calling AddCommand; the wizard never writes those tables.
package splice.app.cli.setup

import splice.app.InstallWiring
import splice.app.cli.AdminSupport
import splice.app.cli.auth.LoginCommand
import splice.configuration.add.AddProfiles
import splice.configuration.add.DaemonUpProbe
import splice.core.terminal.TerminalOutput
import splice.core.topology.CLAUDE_SPLICE_COMMAND
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.launch.install.InstallCommand
import splice.launch.install.InstallLayout
import splice.launch.install.InstallResult
import splice.topology.TopologyLoader
import java.nio.file.Path

/** The `setup` verb, and `setup --update` (SetupUpdate). Production constructs with defaults. */
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
    private val menu = SetupMenu(frame, profiles)
    private val mcpShare = SetupMcpShare(prompts, InstallWiring.units(env), env)
    private val codexHooks = SetupCodexHooks(prompts)
    private val headChoices = SetupHeadChoices(prompts, profiles)
    private val modes = SetupModes(prompts, env)
    private val autoUpdate = SetupAutoUpdate(prompts, env)
    private val seatd = SetupSeatd(prompts, env, effects.seatd)

    /** The post-install OAuth tail, in splice.app.cli.setup since V4-156 (concentration). */
    private val signIn = SetupSignIn(loginHead, env)

    /** The wizard, or with [update] the checkup of an existing install that offers only what is new (SetupUpdate). */
    internal suspend fun setup(update: Boolean = false): Boolean =
        if (update) SetupUpdate(prompts, env, effects, detect).run() else runWizard()

    private suspend fun runWizard(): Boolean = when (val answer = ask()) {
        is WizardAnswer.Cancelled -> frame.cancel(answer.reason)
        is WizardAnswer.Plan -> install(answer)
    }

    /** The questions, up to the operator's go: the plan to install, or the reason the wizard ends without it. */
    private suspend fun ask(): WizardAnswer {
        frame.intro("splice setup")
        val facts = detect()
        menu.printDetected(facts)
        val path = TopologyLoader.configPath(env)
        val bin = InstallLayout().localBin(env)
        val launchers = effects.launchers()
        val picker = effects.heads(profiles, prompts, env)
        // The list of plans is the first question, so leaving it with Escape or Ctrl-C ends setup. While a "Starting
        // point" menu came first, leaving the list meant "none of these" and the wizard went on to its next question.
        val heads = picker.offer(facts, path) ?: return WizardAnswer.Cancelled("cancelled")
        val choices = headChoices.ask(heads)
        val launcherChoice = launchers.offer(prompts, facts.capabilities?.desktop)
            ?: return WizardAnswer.Cancelled("cancelled")
        return confirmPlan(path, facts, launcherChoice, HeadSelection(picker, heads, choices))
    }

    private data class HeadSelection(
        val picker: SetupHeads,
        val heads: List<String>,
        val choices: Map<String, HeadChoice>,
    )

    private fun confirmPlan(
        path: Path,
        facts: SetupFacts,
        launcherChoice: LauncherChoice,
        selection: HeadSelection,
    ): WizardAnswer {
        val bin = InstallLayout().localBin(env)
        val launchers = effects.launchers()
        val local = localModel.offer(path)
        val share = mcpShare.ask(path) ?: return WizardAnswer.Cancelled("cancelled")
        val hooksChoice = codexHooks.offer(facts)
        val extras = SetupExtras(
            local,
            share,
            modes.offer(path).orEmpty(),
            autoUpdate.offer(path),
            hooksChoice,
            seatd.offer(),
        )
        val summary = menu.summaryLines(path, bin, selection.heads, launchers.summaryLines(launcherChoice)) +
            selection.choices.values.map { it.summary } + localModel.summary(local) + mcpShare.summary(share) +
            codexHooks.summary(hooksChoice) + modes.summary(extras.modes) + autoUpdate.summary(extras.autoUpdate)
        frame.note("Summary", summary + seatd.summary(extras.seatd))
        if (!frame.confirm("Install now?", true)) return WizardAnswer.Cancelled("not installing")
        effects.choose(selection.choices)
        return WizardAnswer.Plan(path, launchers, launcherChoice, selection.picker, selection.heads, extras)
    }

    private suspend fun install(plan: WizardAnswer.Plan): Boolean {
        if (!linked()) return false
        val landed = plan.picker.addAll(plan.heads)
        plan.launchers.apply(plan.launcherChoice, frame)
        localModel.install(plan.extras.local)
        seatd.apply(plan.extras.seatd)
        val topology = TopologyLoader.loadOrMaterialize(plan.path)
        mcpShare.save(plan.path, plan.extras.share)
        mcpShare.hub(plan.path, plan.extras.share)
        codexHooks.apply(plan.extras.codexHooks)
        modes.save(plan.path, plan.extras.modes)
        autoUpdate.save(plan.path, plan.extras.autoUpdate)
        // A running daemon read splice.toml before the modes were turned on; restart it so the doctor below and the
        // next session see them. auto_update needs none: the updater reads it live.
        if (plan.extras.modes.isNotEmpty()) effects.restart()
        val ok = signIn.signInPendingHeads(topology)
        headChoices.checkLive(landed, topology, env)
        // The checkup runs with no plan connected too: `claude` on splice's door is a whole setup, and until Nov 23,
        // 2026 that setup skipped the doctor and closed on "Not set up yet." over a working install.
        effects.checkup(env)
        signIn.printNextSteps(topology, if (plan.launcherChoice.cliShortcut) CLAUDE_SPLICE_COMMAND else LAUNCH_CLAUDE)
        // The ONE completion line on the last screen — printNextSteps deliberately has none. "You're
        // set." rather than "Toolkit ready!": splice is not called a toolkit anywhere else.
        frame.outro("You're set.")
        return ok
    }

    /** The install step under its spinner: true when every wrapper is linked. The installer's own lines wait for the
     *  spinner to stop: said while it spun, the first one ran into the spinner's line ("Installingsplice: wrote
     *  starter topology to ..."). */
    private fun linked(): Boolean {
        val said = mutableListOf<String>()
        prompts.spinner.start("Installing")
        val result = Cancellables.runCatchingBestEffort { runInstall(TerminalOutput { said += it }) }
        val installed = result.fold(onSuccess = { it is InstallResult.Linked }, onFailure = { false })
        if (installed) prompts.spinner.stop("Installed wrappers") else prompts.spinner.fail("Install failed")
        said.forEach { frame.step(it.removePrefix("splice: ")) }
        // A refusal prints its sentence on one line, as the install verb does, after the spinner has stopped.
        result.getOrNull()?.let { if (it is InstallResult.Refused) System.err.println("splice: ${it.sentence}") }
        result.exceptionOrNull()?.let { throw it }
        return installed
    }

    /** The starter file, then every command the topology names. `install --all` links `splice` itself with them, so
     *  no second link is made here: the one that was said "installed 'splice'" twice. */
    private fun runInstall(output: TerminalOutput): InstallResult {
        InstallWiring.init(env, output)
        return InstallCommand(output, TerminalOutput(System.err::println)).install("--all", env)
    }
}
