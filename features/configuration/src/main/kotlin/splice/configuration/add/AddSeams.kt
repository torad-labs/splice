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
    public operator fun invoke(key: String, env: EnvReader): Boolean
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

/** The five ways `splice add` reaches outside itself, as app composes them (AddWiring) and a test
 *  fakes them: one contract rather than five constructor parameters on every verb that takes them. */
public data class AddPorts(
    public val login: AddLogin,
    public val install: WrapperInstall,
    public val restart: DaemonRestart,
    public val daemonUp: DaemonUpProbe,
    public val prompt: AddPrompter,
)
