// NEW: compose `splice doctor` with the terminal and the two facts only the executable holds
// (LAYOUT-01). The doctor moved to features/diagnostics; where the running jar is comes from
// AdminSupport, and how a turn reaches a local runtime comes from the provider wiring's headers and
// bearer (LocalProbeInputs), the same transport the boot refusal probes with.
package splice.app

import splice.app.cli.AdminSupport
import splice.app.provider.JdkLocalHttp
import splice.app.provider.LocalProbeInputs
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.diagnostics.doctor.DaemonAnswers
import splice.diagnostics.doctor.DoctorCommand
import splice.diagnostics.doctor.DoctorFixes
import splice.diagnostics.doctor.LocalRuntimeTransport

internal object DoctorWiring {
    private val probeInputs = LocalProbeInputs()

    // One doctor for the console's /api/doctor polls, built on the first one.
    private val console by lazy { command() }

    fun command(): DoctorCommand = DoctorCommand(
        output = TerminalOutput(::println),
        errors = TerminalOutput(System.err::println),
        jar = RunningJar(AdminSupport::selfJar),
        local = LocalRuntimeTransport { key, provider, env ->
            JdkLocalHttp(probeInputs.headers(provider, probeInputs.bearer(key, provider, env)))
        },
    )

    /** The console's /api/doctor body: the `--json` report under the daemon's own environment, reading
     *  the daemon from the [answers] it took in process (V4-230). */
    fun consoleJson(answers: DaemonAnswers): String = console.reportJson(EnvReader(System::getenv), answers)

    /** V4-220 item 4: the fixes the console runs, over the SAME doctor and environment [consoleJson]
     *  reports from, so a fix and the rows that asked for it resolve every path alike. */
    fun fixes(): DoctorFixes = DoctorFixes(console, EnvReader(System::getenv))
}
