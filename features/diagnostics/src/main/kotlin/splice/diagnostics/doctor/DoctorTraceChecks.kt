// NEW: V4-174 — show trace policy and boot-vs-declaration mismatches in doctor.
// V4-387 defaults tracing ON: doctor names opted-out heads and pending restart mismatches, not
// every head that is writing by default. A stopped daemon only supplies the next-start declaration.
package splice.diagnostics.doctor

import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.topology.TopologyKnobLayer
import splice.core.util.EnvReader
import splice.daemonclient.DaemonProbe

private val TRUE_SPELLINGS = setOf("1", "true", "yes", "on")
private val FALSE_SPELLINGS = setOf("0", "false", "no", "off")

/** [env] is the environment the daemon reads, so the retention named is the one the daemon resolves: a head's own
 *  traceRetentionDays when someone set it, else the history window (SpliceConfig.traceKeptDays). */
internal class DoctorTraceChecks(
    private val statePaths: StatePaths,
    private val env: EnvReader = EnvReader(System::getenv),
) {

    /** [running] is absent when the daemon was stopped or its effective config could not be read.
     *  Its missing head is equally unknown, never silently interpreted as trace off. */
    internal fun traceChecks(
        topology: Topology,
        running: Map<String, DaemonProbe.HeadTrace>? = null,
    ): List<DoctorCheck> {
        val config = ConfigService(
            statePaths,
            headOverrides = TopologyKnobLayer(topology).configOverrides(),
            perHeadOverrides = topology.heads.mapValues { (_, head) -> head.overrides },
            envReader = env,
        )
        return topology.heads.mapNotNull { (key, head) ->
            val raw = head.overrides[Knob.TRACE.key]
            val declared = when (raw?.trim()?.lowercase()) {
                null -> Knob.TRACE.flag()
                in FALSE_SPELLINGS -> false
                in TRUE_SPELLINGS -> true
                else -> return@mapNotNull invalidRow(key)
            }
            val booted = running?.get(key)
            if (declared && booted?.enabled != false) return@mapNotNull null
            traceRow(key, declared, booted, config.getConfig(key).traceKeptDays)
        }
    }

    private fun invalidRow(key: String): DoctorCheck = DoctorCheck(
        "trace:$key",
        CheckStatus.FAIL,
        "$key sets overrides.trace to a value that is neither true nor false; no value is echoed",
        "set trace = true or remove the override; trace defaults on at the next start",
    )

    /** Forever is no number of days: the history window kept with no limit reads as such. */
    private fun kept(days: Int): String = if (days == Int.MAX_VALUE) "kept with no time limit" else "kept $days day(s)"

    private fun traceRow(
        key: String,
        declared: Boolean,
        booted: DaemonProbe.HeadTrace?,
        keptDays: Int,
    ): DoctorCheck {
        val pending = booted != null && booted.enabled != declared
        val lead = when {
            booted == null -> "$key will not write its FULL request/response trace on the next start"
            booted.enabled -> "$key still writes until the next restart: its FULL request/response trace"
            declared ->
                "$key will write after the next restart; nothing is written now. " +
                    "Its FULL request/response trace"
            else -> "$key does not write its request and reply trace"
        }
        val setting = if (declared) "" else " Its head sets overrides.trace = false."
        val tense = if (booted?.enabled == true) "It records" else "When enabled, it records"
        val detail = "$lead.$setting $tense every request it receives, every upstream attempt with the exact body " +
            "it sent (credentials redacted) and the raw response, and every frame it streamed back, " +
            "as one file per UTC day; the declared directory for the next start is " +
            "${statePaths.traceDir} (owner-only); declared files are ${kept(keptDays)} " +
            "on the next start; read with `splice trace $key`"
        val fix = if (declared) {
            "restart to begin tracing $key; `splice trace $key --purge` deletes what was written"
        } else {
            "remove overrides.trace = false from [heads.$key] and restart to resume tracing; " +
                "`splice trace $key --purge` deletes what was written"
        }
        return DoctorCheck(
            "trace:$key",
            CheckStatus.WARN,
            detail,
            fix,
            notes = DoctorCheckNotes(pendingRestart = pending),
        )
    }
}
