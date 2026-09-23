// NEW: the ROLE the CLI's process edge injects, named (HD-22, wave 4b). Its siblings left with what
// they isolate (LAYOUT-01): DoctorProbe to features/diagnostics with the checks, SignalSend to
// features/lifecycle with the stop ladder. What stays is the restart setup and add are handed.
package splice.app.cli

/** Restarts the daemon the plain way (`splice restart`: stop, then cold start from this shell). */
internal fun interface DaemonRestart {
    operator fun invoke(): Boolean
}
