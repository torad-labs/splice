// NEW: V4-220 item 4 (2026-09-25) — the doctor fixes the console may run, run by the daemon in its
// own process and environment: the same environment its /api/doctor report reads, so the fix and
// the rows that asked for it agree on every path.
//
// THE ANSWER IS DOCTOR RE-RUN, never the verb's own return. A fix counts as applied only when the
// run after it finds no row still carrying its id; a verb that returned while a row still asks for it
// is a refusal naming how many rows remain, and a verb that refused is a refusal with its own
// sentence. Both carry the report of that one run, so the console shows the state it would read on
// its next poll.
//
// Only DoctorFix's members run here, and each is a CLI verb that is safe unattended: `install --all`
// creates or relinks splice's own wrapper symlinks and refuses (InstallRefused) rather than replacing
// a foreign file. The PATH row, a foreign file, access problems and the shim stay text: their remedy
// is an rc edit, a move or a reinstall that only the operator can make.
package splice.diagnostics.doctor

import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.launch.install.InstallCommand
import splice.launch.install.InstallFailureText

/** One fix's answer: [report] is the `doctor --json` text of the run taken AFTER the fix. */
public sealed class DoctorFixOutcome {
    public abstract val fix: DoctorFix
    public abstract val report: String

    /** The run after the fix finds no row that still carries [fix]. */
    public data class Applied(override val fix: DoctorFix, override val report: String) : DoctorFixOutcome()

    /** The fix refused, or ran and left rows that still carry it; [text] is one sentence. */
    public data class Refused(
        override val fix: DoctorFix,
        val text: String,
        override val report: String,
    ) : DoctorFixOutcome()
}

public class DoctorFixes(private val doctor: DoctorCommand, private val env: EnvReader) {
    // The verb's progress lines are its terminal output; what the console reads is the re-run.
    private val install = InstallCommand(TerminalOutput { }, TerminalOutput { })

    /** [answers] is the daemon's own (V4-230): the run after the fix reads the daemon from them, as
     *  GET /api/doctor does. */
    public fun run(fix: DoctorFix, answers: DaemonAnswers): DoctorFixOutcome {
        val (verb, ran) = when (fix) {
            DoctorFix.INSTALL_ALL ->
                FIX_RELINK to Cancellables.runCatchingCleanup { install.install(INSTALL_ALL_ARG, env) }
        }
        val after = doctor.collect(env, answers = answers)
        val report = doctor.reportJson(after, env)
        val remaining = after.sections.flatMap { it.second }.count { it.fixId == fix }
        val refusal = ran.exceptionOrNull()?.let(InstallFailureText::render)
            ?: "$verb ran, but $remaining doctor row(s) still call for it".takeIf { remaining > 0 }
        return if (refusal == null) {
            DoctorFixOutcome.Applied(fix, report)
        } else {
            DoctorFixOutcome.Refused(fix, refusal, report)
        }
    }
}

private const val INSTALL_ALL_ARG = "--all"
