// NEW: the ROLE the CLI's process edge injects, named (HD-22, wave 4b). Its doctor sibling,
// DoctorProbe, moved to features/diagnostics with the checks it isolates (LAYOUT-01).
package splice.app.cli

/**
 * Delivers one signal to the daemon process — `destroy()` (TERM) or `destroyForcibly()` (KILL).
 *
 * The BOOLEAN is the whole reason this is a seam rather than a hardcoded call. Both returns used to
 * be discarded while the preceding line already told the operator the signal had been sent, so an
 * undelivered TERM read as "the daemon did not stop" with no hint that nothing was ever signalled.
 * False means not delivered, and the escalation ladder is required to say so.
 */
internal fun interface SignalSend {
    operator fun invoke(handle: ProcessHandle): Boolean
}
