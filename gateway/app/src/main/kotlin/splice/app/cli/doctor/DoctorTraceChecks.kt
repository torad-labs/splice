// NEW: V4-174 — the doctor row for a head whose full request/response trace is ON. The trace is
// opt-in (operator 2026-09-20: "optin of course"), and opt-in has to stay VISIBLE for the same
// reason V4-173's wire tap does: a switch flipped for one investigation and forgotten is the one
// that reads as surveillance a month later, and this one writes whole conversations to disk. So
// while `[heads.KEY.overrides] trace = true`, every `splice doctor` run says which head is traced,
// where the files are, how long they are kept, who can read them, and how to purge them. A head
// with no such override, or one that says false, never has a row.
//
// Reads the topology's OWN declaration (the overrides table), not the daemon: the row must be right
// with the daemon stopped, and the knob is restart-required, so the declaration is what the next
// boot will do. A value that is neither true nor false FAILS — a trace that is "maybe on" is worse
// than either answer.
package splice.app.cli.doctor

import splice.app.cli.CheckStatus
import splice.app.cli.DoctorCheck
import splice.core.config.Knob
import splice.core.config.StatePaths
import splice.core.topology.Topology

private val TRUE_SPELLINGS = setOf("1", "true", "yes", "on")
private val FALSE_SPELLINGS = setOf("0", "false", "no", "off")

internal class DoctorTraceChecks(private val statePaths: StatePaths) {

    internal fun traceChecks(topology: Topology): List<DoctorCheck> =
        topology.heads.mapNotNull { (key, head) ->
            val declared = head.overrides[Knob.TRACE.key] ?: return@mapNotNull null
            when (declared.trim().lowercase()) {
                in FALSE_SPELLINGS -> null
                in TRUE_SPELLINGS -> onRow(key, head.overrides[Knob.TRACE_RETENTION_DAYS.key])
                else -> DoctorCheck(
                    "trace:$key",
                    CheckStatus.FAIL,
                    "$key sets overrides.trace = \"$declared\", which is neither true nor false",
                    "set trace = true to write the head's full request/response trace, or remove it",
                )
            }
        }

    private fun onRow(key: String, retention: String?): DoctorCheck {
        val days = retention?.trim()?.toLongOrNull() ?: Knob.TRACE_RETENTION_DAYS.default as Long
        return DoctorCheck(
            "trace:$key",
            CheckStatus.WARN,
            "$key writes its FULL request/response trace (overrides.trace): every request it receives, " +
                "every upstream attempt with the exact body it sent (credentials redacted) and the raw " +
                "response, and every frame it streamed back — one file per UTC day under " +
                "${statePaths.traceDir} (owner-only), kept $days day(s); read with `splice trace $key`",
            "remove overrides.trace from [heads.$key] (and restart) when the investigation is over; " +
                "`splice trace $key --purge` deletes what was written",
        )
    }
}
