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
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.launch.install.InstallLayout
import splice.launch.install.InstallResult
import splice.topology.TopologyLoader
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
    private val menu = SetupMenu(frame, profiles)

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
        menu.printDetected(facts)
        val options = menu.startOptions(facts)
        val picked = prompts.choose(options, menu.initialIndex(facts, options))
        val start = menu.chosenStart(picked) ?: return WizardAnswer.Cancelled("cancelled")
        val path = TopologyLoader.configPath(env)
        val bin = InstallLayout().localBin(env)
        val lanes = effects.lanes(prompts)
        val picker = effects.heads(profiles, prompts, env)
        val heads = picker.offer(facts, path)
        val lane = lanes.ask(heads)
        val local = localModel.offer(path)
        val summary = menu.summaryLines(start, path, bin, heads, lanes.summaryLine(lane)) + localModel.summary(local)
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
