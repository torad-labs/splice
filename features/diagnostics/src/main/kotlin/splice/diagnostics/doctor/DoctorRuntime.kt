// NEW: (JW-05, split from DoctorCommand.kt — the file sits at detekt's function budget): the
// doctor runtime section. Every other section reads configuration and presence; this one reads
// what actually HAPPENED — the G20 health counters (/api/heads) and the per-head perf JSONL
// outcome tail — so a fully-configured install with dying turns cannot print "Everything
// checks out."
package splice.diagnostics.doctor

import splice.core.config.StatePaths
import splice.core.topology.ProviderFamilyRule
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.daemonclient.DaemonProbe
import splice.topology.TopologyStatePaths

/** The doctor runtime section as a constructed collaborator (Kotlin style law, 2026-08-15: main
 *  sources carry no top-level functions). Stateless — DoctorCommand builds one and asks it; every
 *  member keeps the old function's name so the diff is a receiver insertion. */
internal class DoctorRuntime {

    /** JW-05: the runtime section. Honest-severity rule as in auth: everything here is WARN at
     *  worst — a runtime error count is a diagnosis, never a config failure the exit code should
     *  block on. Counters are since-last-restart (G20 resets them); the perf tail is recency-framed
     *  (last N turns), never lifetime totals. Fail-open at every hop: no daemon, no key, or an
     *  unreachable endpoint each degrade to one INFO row, never a crash and never a fabricated OK. */
    internal fun runtimeChecks(
        snapshot: DaemonSnapshot,
        envReader: EnvReader,
        reads: DaemonReads,
        topology: Topology? = null,
    ): List<DoctorCheck> {
        val statePaths = TopologyStatePaths(envReader).current()
        snapshot.unanswered?.let { return listOf(skipped(it)) }
        // The key is read ONCE, inside the loopback read (review #94, F154: a guard-and-use double read
        // raced key rotation), and a key never minted is not a key that could not be read (DR-174).
        return when (val heads = reads.heads(snapshot.port, envReader)) {
            is DaemonRead.Answered -> heads.value.flatMap { h ->
                headRuntimeRows(h, statePaths, providerName(h.key, topology))
            }
            is DaemonRead.KeyUnreadable -> listOf(skipped("mgmt-key unreadable: ${heads.reason}"))
            DaemonRead.KeyAbsent -> listOf(skipped("mgmt-key not minted yet"))
            DaemonRead.Unreachable -> listOf(skipped("/api/heads unreachable"))
        }
    }

    private fun skipped(why: String) = DoctorCheck("runtime", CheckStatus.INFO, "skipped ($why)")

    internal fun headRuntimeRows(
        h: DaemonProbe.HeadRuntime,
        statePaths: StatePaths,
        providerName: String = "the provider",
    ): List<DoctorCheck> {
        val rates = h.rateLimit
        val limited = rates != null && (rates.providerTurns > 0 || rates.heldTurns > 0)
        val hasEvents = h.providerErrors > 0 || h.localOriginErrors > 0
        val counters = if (limited || hasEvents) {
            DoctorCheck(
                "head ${h.key} errors",
                CheckStatus.WARN,
                if (limited) {
                    rateLimitSentence(h, providerName)
                } else if (rates != null) {
                    "${h.key}: ${h.providerErrors} provider / ${h.localOriginErrors} local diagnostic error events " +
                        "since the restart. No turns ended on a rate limit or cooldown hold."
                } else {
                    "${h.providerErrors} provider / ${h.localOriginErrors} local error(s) since last restart"
                },
                "splice logs --head ${h.key} --tail 50",
                fixKind = FixKind.COMMAND,
            )
        } else {
            DoctorCheck("head ${h.key} errors", CheckStatus.OK, "none since last restart")
        }
        return listOf(counters, DoctorProbeWrite().perfTailRow(h.key, statePaths.perfStatsFile(h.key)))
    }

    private fun rateLimitSentence(h: DaemonProbe.HeadRuntime, provider: String): String {
        val rates = checkNotNull(h.rateLimit)
        val total = rates.providerTurns + rates.heldTurns
        val turns = if (total == 1L) "turn" else "turns"
        return "${h.key}: $total $turns hit $provider's rate limit after the restart; " +
            "splice held back ${rates.heldTurns} of them while it cooled down. " +
            "Diagnostic error events: ${h.providerErrors} provider / ${h.localOriginErrors} local."
    }

    private fun providerName(head: String, topology: Topology?): String {
        val key = topology?.heads?.get(head)?.provider ?: return "the provider"
        val provider = topology.providers[key] ?: return "the provider"
        val family = ProviderFamilyRule().of(key, provider)
        return when (family) {
            null -> key
            "anthropic" -> "Anthropic"
            "local" -> "the local runtime"
            else -> splice.core.topology.ApiKeyProviderRegistry.row(family)?.label
                ?: family.replaceFirstChar { it.uppercaseChar() }
        }
    }

    /** The TURN-PATH verdict — ranked ABOVE the head counters by its caller, because it outranks them:
     *  during the 91h wedge every counter was perfect (4 ready, 0 failed) while not one turn could
     *  complete, so a doctor reading only counters certifies a total outage as healthy. This file's
     *  reason for existing, applied to liveness: a configured install with dying turns must not print
     *  "Everything checks out."
     *
     *  Null on a pre-probe daemon that omits `ok` — absent evidence is not evidence of health, so
     *  nothing is claimed. FAIL (not WARN) when a head is named: unlike the rest of this file's
     *  counters, a wedged turn path is not a diagnosis of degraded quality, it is the outage.
     *  Lives here rather than in DoctorCommand.kt only because that file sits at detekt's function
     *  budget — same reason JW-05 split this file off in the first place. */
    internal fun turnPathCheck(h: HealthView): DoctorCheck? = when {
        h.ok == null -> null
        h.turnPathStalled.isNotEmpty() -> DoctorCheck(
            "turn path",
            CheckStatus.FAIL,
            "WEDGED on ${h.turnPathStalled.joinToString(", ")}: requests are accepted but never " +
                "answered (loopback probes timed out). This is the 91h-outage signature. After a restart, " +
                "`splice logs --head <key> --tail 100` says why.",
            "splice restart",
            fixKind = FixKind.COMMAND,
        )
        // ok:false naming no head is still a refusal to certify health; reporting it beats falling
        // through to the counters, which is the exact false green being fenced off.
        h.ok == false -> DoctorCheck("turn path", CheckStatus.WARN, "the daemon reports ok:false without naming a head")
        else -> DoctorCheck("turn path", CheckStatus.OK, "loopback probes are completing")
    }
}
