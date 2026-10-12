// NEW: v0.4.0 FEATURES.md §1 — the seams `splice add` reaches the world through — the terminal
// prompt, the login flow, the wrapper linker, the daemon probe and the restart — each named for its
// role so the command is tested without a terminal, a browser or a daemon. Split from AddCommand.kt
// (concentration, 2026-09-13). Public since LAYOUT-01: app composes each one (AddWiring), and the
// console prompter itself stays in app beside the stdin it reads.
package splice.configuration.add

import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader

/** Answers a question with the operator's line, or [default] when there is no terminal. */
public fun interface AddPrompter {
    public operator fun invoke(question: String, default: String): String
}

/** The sign-in for a head that is not in the topology yet; production runs the login verb's flow. */
public fun interface AddLogin {
    public suspend operator fun invoke(key: String, provider: ProviderConfig, topology: Topology): Boolean
}

/** Links the wrapper command for a head (`splice install <key>`). */
public fun interface WrapperInstall {
    public operator fun invoke(key: String, env: EnvReader): AddLinked
}

/** Whether a daemon answers on the control port. */
public fun interface DaemonUpProbe {
    public operator fun invoke(port: Int): Boolean
}

/** Restarts the daemon the plain way (`splice restart`: stop, then cold start from this shell). In
 *  app's cli package until LAYOUT-01; setup hands the same role to its own restart. */
public fun interface DaemonRestart {
    public operator fun invoke(): Boolean
}

/** A user-managed local runtime's answer about the rows an add declares: the window it serves for each row it reports
 *  one for ([windows], shown beside the declaration), and the rows its own rules refuse ([refused], id to the reason): a
 *  window past what it allows, a model it says cannot call tools. Nothing answering is an empty answer. */
public data class LocalRowsAnswer(
    public val windows: Map<String, Long> = emptyMap(),
    public val refused: Map<String, String> = emptyMap(),
)

/** Asks the runtime at [baseUrl] about [rows], id to the declared window (null when none is declared), the way boot asks
 *  it (LocalRuntimeProbe), since a plain /v1/models leaves the window out on Ollama and LM Studio. */
public fun interface LocalRuntimeRows {
    public operator fun invoke(baseUrl: String, rows: Map<String, Long?>): LocalRowsAnswer
}

/** The result of checking the newly written command, without exposing its raw output. */
public data class AddLiveResult(public val ok: Boolean, public val detail: String)

/** One bounded turn through the installed head command, on the first model the person chose, after save and activation. */
public fun interface AddLiveTurn {
    public operator fun invoke(command: String, model: String, env: EnvReader): AddLiveResult
}

/** Runs the checkup (`splice doctor`) once the head is saved; true when it found nothing to fail. */
public fun interface AddDoctor {
    public operator fun invoke(env: EnvReader): Boolean
}

/** The ways `splice add` reaches outside itself, as app composes them (AddWiring) and a test
 *  fakes them: one contract rather than five constructor parameters on every verb that takes them. */
public data class AddPorts(
    public val login: AddLogin,
    public val install: WrapperInstall,
    public val restart: DaemonRestart,
    public val daemonUp: DaemonUpProbe,
    public val prompt: AddPrompter,
    public val liveTurn: AddLiveTurn = AddLiveTurn { _, _, _ ->
        AddLiveResult(false, "the live checker is not configured; run the head's command to check it")
    },
    public val doctor: AddDoctor = AddDoctor { true },
)
