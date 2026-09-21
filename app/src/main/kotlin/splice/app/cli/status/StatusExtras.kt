// NEW: v0.4.0 FEATURES.md §11 — the lines `splice status` prints beyond the head table: the jar it runs
// (DR-86: unreadable says so) and, for a head holding more than one account, which one it is on.
// Split from StatusCommand.kt (concentration, 2026-09-13).
package splice.app.cli.status

import splice.core.util.EnvReader
import splice.core.util.SafeFailureText

internal class StatusExtras(private val accountPools: AccountPoolRead) {
    private val accountText = AccountPoolText()

    /** v0.4.0 (FEATURES.md §11): a head holding more than one account says which one it is on; a
     *  projection this process could not read says so rather than passing for "one account". */
    fun printAccounts(port: Int, envReader: EnvReader) {
        val pools = accountPools(port, envReader)
        if (pools == null) println("  accounts  ${YELLOW}not readable$RESET — the daemon's /api/auth did not answer")
        if (!pools.isNullOrEmpty()) println()
        pools?.forEach { (key, view) -> println("  accounts  $BOLD$key$RESET ${accountText.summary(view)}") }
    }

    /** DR-86: the status table is a reporter — a jar it cannot stat must say so, not render as
     *  installed (the doctor jarCheck twin). Internal for the permanent arm (codex redo). */
    fun jarLine(): String {
        val jar = AdminSupport.selfJar() ?: return "not installed — run: splice install"
        val failure = AdminSupport.jarAccessFailure(jar)
            ?: return jar.toString()
        return "$jar is unreadable (${SafeFailureText.render(failure)}) — fix access to it"
    }
}
