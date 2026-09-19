// NEW: the four ROLES the setup wizard injects, named. They lived as raw function types on
// SetupCommand's constructor, which kt-no-lambda-seam rejects for a reason this file's own history
// proves: `suspend (String) -> Boolean` says nothing about what calling it DOES, and SetupCommandTest
// left it at its default for weeks, so the wizard ran a real OAuth sign-in on every `:app:test` —
// opening the operator's browser and hanging on a loopback callback. A seam called HeadSignIn is
// one a test reads and thinks twice about; `(String) -> Boolean` is one it skips. They live here
// rather than beside the class because SetupCommand.kt already carries five types and the
// concentration wall is watching it.
package splice.app.cli

import splice.app.cli.prompt.SelectOption
import splice.app.cli.prompt.SelectOutcome

/**
 * Signs ONE head in, answering whether it ended up authenticated.
 *
 * The heaviest seam in the wizard: its production implementation opens a browser, binds a loopback
 * port, and blocks until the vendor redirects back. Every test must pass its own.
 */
internal fun interface HeadSignIn {
    suspend operator fun invoke(head: String): Boolean
}

/** Reads the machine once — installed wrappers, present credentials, a daemon already up. */
internal fun interface SetupProbe {
    operator fun invoke(): SetupFacts
}

/** Asks the operator which starting point to take, or reports that they cancelled. */
internal fun interface StartChoice {
    operator fun invoke(options: List<SelectOption<SetupStart>>, initialIndex: Int): SelectOutcome<SetupStart>
}
