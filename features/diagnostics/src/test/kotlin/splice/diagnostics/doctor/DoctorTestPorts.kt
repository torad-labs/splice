// The executable-held facts a doctor test answers itself (LAYOUT-01). In production app hands the
// doctor where the running jar is and how a turn reaches a local runtime (DoctorWiring); a test
// answers both, and prints through println so a System.out capture reads the report as before.
package splice.diagnostics.doctor

import splice.core.terminal.TerminalOutput
import splice.upstream.transport.LocalHttp

internal object DoctorTestPorts {
    /** A dev build: running from classes, no jar. */
    val noJar = RunningJar { null }

    /** Nothing answers at any local runtime's base URL — what a real probe sees with none running. */
    val silentLocal = LocalRuntimeTransport { _, _, _ -> LocalHttp { _, _, _ -> null } }

    /** Every local runtime answers through [http]. */
    fun local(http: LocalHttp) = LocalRuntimeTransport { _, _, _ -> http }

    /** The doctor with its lines on System.out and System.err, as the CLI prints them. */
    fun doctor() = DoctorCommand(TerminalOutput(::println), TerminalOutput(System.err::println), noJar, silentLocal)

    /** The configuration section with no local runtime answering. */
    fun configChecks() = DoctorConfigChecks(DoctorLocalRuntime(silentLocal))
}
