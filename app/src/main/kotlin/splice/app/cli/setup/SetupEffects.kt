// NEW: the steps of `splice setup` that change the machine (SetupEffects), and `splice add` as setup runs it
// (CommandAdd). Split from SetupCommand.kt (Nov 23, 2026, concentration), which keeps the flow.
package splice.app.cli.setup

import splice.app.AddWiring
import splice.app.LifecycleWiring
import splice.app.cli.claude.ClaudeRetire
import splice.app.cli.desktop.DaemonUp
import splice.app.cli.desktop.DoorClient
import splice.configuration.add.AddDoctor
import splice.configuration.add.AddProfiles
import splice.configuration.add.DaemonRestart
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.launch.install.GeminiShim
import splice.launch.install.GeminiShimLaid
import splice.lifecycle.seatd.SeatdCompanion
import splice.lifecycle.seatd.SeatdInstaller

/** The steps of `setup` that change the machine, beside install: the profile add (and retiring the command it links),
 *  the daemon restart and the launchers. Not prompts, which is the line SetupPrompts draws. Each reads through [env],
 *  not a fresh `EnvReader(System::getenv)`, so a test's hermetic environment is the one every step sees (V4-177). */
internal class SetupEffects(
    private val env: EnvReader,
    /** The `splice add` arguments each head's answers add (SetupHeadChoices): its models and their windows. */
    private val choices: MutableMap<String, List<String>> = mutableMapOf(),
    private val add: ProfileAdd = CommandAdd(choices),
    val restart: DaemonRestart = DaemonRestart { LifecycleWiring.restart() },
    private val launchers: SetupLaunchers = SetupLaunchers(env),
    val seatd: SeatdCompanion = SeatdInstaller(env),
) {
    fun heads(profiles: AddProfiles, prompts: SetupPrompts, env: EnvReader): SetupHeads =
        SetupHeads(profiles, prompts.pickHeads, add, restart, prompts.hasConsole, env, prompts.frame)

    fun launchers(): SetupLaunchers = launchers

    /** Upkeep, never an offer: the shim is laid again where it is missing or stale. */
    fun refreshGeminiShim(): String? = when (val laid = GeminiShim(env).lay()) {
        is GeminiShimLaid.Written -> "Laid the Antigravity shim at ${laid.path}"
        is GeminiShimLaid.Failed -> "Could not lay the Antigravity shim (${laid.why})"
        GeminiShimLaid.Current -> null
    }

    /** `splice doctor`, once, at the end of setup: with no plan connected as much as with three. */
    fun checkup(env: EnvReader) = add.checkup(env)

    /** What each head's add carries beyond `--yes`, from the person's answers. */
    fun choose(answers: Map<String, HeadChoice>) {
        choices.clear()
        answers.forEach { (name, choice) -> choices[name] = choice.addArgs }
    }
}

/** `splice add <profile> --yes` as setup runs it. Each add would restart the daemon and end on `splice doctor`: setup
 *  restarts once after its last head and runs the checkup once too. Until Nov 11, 2026 a setup that added three heads
 *  printed the whole report three times. */
private class CommandAdd(private val choices: Map<String, List<String>>) : ProfileAdd {
    override suspend fun invoke(name: String): Boolean =
        AddWiring.add(restart = DaemonRestart { true }, doctor = AddDoctor { true })
            .add(listOf(name, "--yes") + choices[name].orEmpty(), EnvReader(System::getenv))

    override fun checkup(env: EnvReader) {
        AddWiring.checkup()(env)
    }

    /** `splice claude retire <key>...`, on the daemon setup just restarted onto the new heads. */
    override fun retire(keys: List<String>, env: EnvReader) {
        val errors = TerminalOutput(System.err::println)
        val client = DoorClient("claude", env, errors, DaemonUp(LifecycleWiring::ensureDaemon))
        ClaudeRetire(client, TerminalOutput(::println)).run(CLAUDE_RETIRE_VERB, keys)
    }
}

// The verb `splice claude retire` runs on the daemon.
private const val CLAUDE_RETIRE_VERB = "retire"
