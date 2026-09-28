// NEW: V4-174 — keep an opted-in full trace visible, including a boot-vs-declaration mismatch.
// A daemon that cannot answer contributes no running claim: only its next-start declaration is known.
package splice.diagnostics.doctor

import splice.core.config.Knob
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.daemonclient.DaemonProbe

private val TRUE_SPELLINGS = setOf("1", "true", "yes", "on")
private val FALSE_SPELLINGS = setOf("0", "false", "no", "off")

internal class DoctorTraceChecks(private val statePaths: StatePaths) {

    /** [running] is absent when the daemon was stopped or its effective config could not be read.
     *  Its missing head is equally unknown, never silently interpreted as trace off. */
    internal fun traceChecks(
        topology: Topology,
        running: Map<String, DaemonProbe.HeadTrace>? = null,
    ): List<DoctorCheck> = topology.heads.mapNotNull { (key, head) ->
        val raw = head.overrides[Knob.TRACE.key]
        val declared = when (raw?.trim()?.lowercase()) {
            null, in FALSE_SPELLINGS -> false
            in TRUE_SPELLINGS -> true
            else -> return@mapNotNull invalidRow(key)
        }
        val booted = running?.get(key)
        if (!declared && booted?.enabled != true) return@mapNotNull null
        val declaredDays = head.overrides[Knob.TRACE_RETENTION_DAYS.key]?.trim()?.toLongOrNull()
            ?: Knob.TRACE_RETENTION_DAYS.default as Long
        traceRow(key, declared, booted, declaredDays)
    }

    private fun invalidRow(key: String): DoctorCheck = DoctorCheck(
        "trace:$key",
        CheckStatus.FAIL,
        "$key sets overrides.trace to a value that is neither true nor false; no value is echoed",
        "set trace = true to write the head's full request/response trace, or remove it",
    )

    private fun traceRow(
        key: String,
        declared: Boolean,
        booted: DaemonProbe.HeadTrace?,
        declaredDays: Long,
    ): DoctorCheck {
        val pending = booted != null && booted.enabled != declared
        val lead = when {
            booted == null -> "$key will write its FULL request/response trace on the next start"
            booted.enabled && declared -> "$key writes its FULL request/response trace (overrides.trace)"
            booted.enabled -> "$key still writes until the next restart: its FULL request/response trace"
            else -> "$key will write after the next restart; nothing is written now. Its FULL request/response trace"
        }
        val detail = "$lead: every request it receives, every upstream attempt with the exact body " +
            "it sent (credentials redacted) and the raw response, and every frame it streamed back, " +
            "as one file per UTC day; the declared directory for the next start is " +
            "${statePaths.traceDir} (owner-only); declared files are kept $declaredDays day(s) " +
            "on the next start; read with `splice trace $key`"
        val fix = if (declared) {
            "remove overrides.trace from [heads.$key] (and restart) when the investigation is over; " +
                "`splice trace $key --purge` deletes what was written"
        } else {
            "restart to stop new trace writes; `splice trace $key --purge` deletes what was written"
        }
        return DoctorCheck("trace:$key", CheckStatus.WARN, detail, fix, pendingRestart = pending)
    }
}
