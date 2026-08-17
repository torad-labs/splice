// NEW: the two ROLES the CLI's process and HTTP edges inject, named (HD-22, wave 4b).
package splice.app.cli

import java.net.HttpURLConnection

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

/**
 * Reads the control-plane response off an already-connected [HttpURLConnection].
 *
 * Runs INSIDE the request helper's try/finally, which is the contract the shape hides: the
 * connection is disconnected the moment this returns, so anything the caller needs must be
 * materialized here rather than handed back as a live stream.
 */
internal fun interface ResponseRead<T> {
    operator fun invoke(connection: HttpURLConnection): T
}

/**
 * One doctor check group, run so that a crash inside it becomes a FAIL row rather than the end of
 * the report.
 *
 * Both halves matter and neither is optional: a crashing check must not kill the report, and it
 * must not masquerade as healthy either. The wrapper turns a throw into a `doctor` FAIL naming the
 * exception, which is why every check group goes through one of these.
 */
internal fun interface DoctorProbe {
    operator fun invoke(): List<DoctorCheck>
}
