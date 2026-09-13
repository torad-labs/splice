// NEW: v0.4.0 FEATURES.md §1 — the seams `splice add` reaches the world through — the terminal
// prompt, the login flow, the wrapper linker and the daemon probe — each named for its role so the
// command is tested without a terminal, a browser or a daemon. Split from AddCommand.kt
// (concentration, 2026-09-13).
package splice.app.cli

import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader

/** Answers a question with the operator's line, or [default] when there is no terminal. */
internal fun interface AddPrompter {
    operator fun invoke(question: String, default: String): String
}

internal class ConsolePrompter : AddPrompter {
    override fun invoke(question: String, default: String): String {
        if (System.console() == null) return default
        print("$question ${if (default.isEmpty()) "" else "[$default] "}")
        val line = Cancellables.runCatchingCancellable { readlnOrNull()?.trim() }.getOrNull()
        return line?.ifEmpty { default } ?: default
    }
}

/** The sign-in for a head that is not in the topology yet; production runs the login verb's flow. */
internal fun interface AddLogin {
    suspend operator fun invoke(key: String, provider: ProviderConfig, topology: Topology): Boolean
}

/** Links the wrapper command for a head (`splice install <key>`). */
internal fun interface WrapperInstall {
    operator fun invoke(key: String, env: EnvReader): Boolean
}

/** Whether a daemon answers on the control port. */
internal fun interface DaemonUpProbe {
    operator fun invoke(port: Int): Boolean
}
