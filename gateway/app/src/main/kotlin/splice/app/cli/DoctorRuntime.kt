// NEW: (JW-05, split from DoctorCommand.kt — the file sits at detekt's function budget): the
// doctor runtime section. Every other section reads configuration and presence; this one reads
// what actually HAPPENED — the G20 health counters (/api/heads) and the per-head perf JSONL
// outcome tail — so a fully-configured install with dying turns cannot print "Everything
// checks out."
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import java.nio.file.Files
import java.nio.file.Path

/** The doctor runtime section as a constructed collaborator (Kotlin style law, 2026-08-15: main
 *  sources carry no top-level functions). Stateless — DoctorCommand builds one and asks it; every
 *  member keeps the old function's name so the diff is a receiver insertion. */
internal class DoctorRuntime {

    /** JW-05: the runtime section. Honest-severity rule as in auth: everything here is WARN at
     *  worst — a runtime error count is a diagnosis, never a config failure the exit code should
     *  block on. Counters are since-last-restart (G20 resets them); the perf tail is recency-framed
     *  (last N turns), never lifetime totals. Fail-open at every hop: no daemon, no key, or an
     *  unreachable endpoint each degrade to one INFO row, never a crash and never a fabricated OK. */
    internal fun runtimeChecks(snapshot: DaemonSnapshot, envReader: (String) -> String?): List<DoctorCheck> {
        val statePaths = StatePaths(envReader = envReader)
        // Read the key ONCE (review #94, F154): the old guard-and-use double read raced key rotation —
        // a key emptying between reads threw checkNotNull, and `guarded` printed a FAIL row,
        // contradicting this section's own contract that an unreadable key degrades to INFO.
        val key = readMgmtKey(statePaths)
        val skip = when {
            !snapshot.running -> "skipped (daemon stopped)"
            key == null -> "skipped (mgmt-key unreadable)"
            else -> null
        }
        if (skip != null) return listOf(DoctorCheck("runtime", CheckStatus.INFO, skip))
        val heads = ControlPlaneClient.headsRuntime(snapshot.port, checkNotNull(key))
            ?: return listOf(DoctorCheck("runtime", CheckStatus.INFO, "skipped (/api/heads unreachable)"))
        return heads.flatMap { h -> headRuntimeRows(h, statePaths) }
    }

    private fun readMgmtKey(statePaths: StatePaths): String? =
        Cancellables.runCatchingCancellable { Files.readString(statePaths.mgmtKeyFile).trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() }

    internal fun headRuntimeRows(
        h: ControlPlaneClient.HeadRuntime,
        statePaths: StatePaths,
    ): List<DoctorCheck> {
        val counters = if (h.providerErrors > 0 || h.localOriginErrors > 0) {
            DoctorCheck(
                "head ${h.key} errors",
                CheckStatus.WARN,
                "${h.providerErrors} provider / ${h.localOriginErrors} local error(s) since last restart",
                "splice logs --head ${h.key} --tail 50",
            )
        } else {
            DoctorCheck("head ${h.key} errors", CheckStatus.OK, "none since last restart")
        }
        return listOf(counters, perfTailRow(h.key, statePaths.perfStatsFile(h.key)))
    }

    /** Last-N turn outcomes from the per-head perf JSONL — "last failure: 4m ago (upstream_failed)"
     *  is the sentence doctor exists to say. Missing/empty file = INFO (a fresh head has no turns). */
    internal fun perfTailRow(headKey: String, perfFile: Path): DoctorCheck {
        val rows = Cancellables.runCatchingCancellable {
            Files.readAllLines(perfFile).takeLast(PERF_TAIL_TURNS).mapNotNull { line -> perfRow(line) }
        }.getOrNull().orEmpty()
        if (rows.isEmpty()) return DoctorCheck("head $headKey turns", CheckStatus.INFO, "no turns recorded yet")
        val failures = rows.filter { (outcome, _) -> outcome != "ok" }
        if (failures.isEmpty()) {
            return DoctorCheck("head $headKey turns", CheckStatus.OK, "last ${rows.size} turn(s) clean")
        }
        val (outcome, ts) = failures.last()
        val ageMin = ((System.currentTimeMillis() - ts) / MS_PER_MINUTE).coerceAtLeast(0)
        return DoctorCheck(
            "head $headKey turns",
            CheckStatus.WARN,
            "${failures.size} of last ${rows.size} turn(s) failed — last failure: ${ageMin}m ago ($outcome)",
            "splice logs --head $headKey --tail 50",
        )
    }

    /** One perf JSONL row -> (outcome, ts); null on a malformed line (tail readers stay tolerant). */
    internal fun perfRow(line: String): Pair<String, Long>? = Cancellables.runCatchingCancellable {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
        val outcome = JsonScalars.str(obj, "outcome")
        if (outcome == null) null else outcome to (JsonScalars.long(obj, "ts") ?: 0L)
    }.getOrNull()

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
    internal fun turnPathCheck(h: ControlPlaneClient.HealthView): DoctorCheck? = when {
        h.ok == null -> null
        h.turnPathStalled.isNotEmpty() -> DoctorCheck(
            "turn path",
            CheckStatus.FAIL,
            "WEDGED on ${h.turnPathStalled.joinToString(", ")} — requests are accepted but never " +
                "answered (loopback probes timed out). This is the 91h-outage signature.",
            "splice restart (then: splice logs --head <key> --tail 100)",
        )
        // ok:false naming no head is still a refusal to certify health; reporting it beats falling
        // through to the counters, which is the exact false green being fenced off.
        h.ok == false -> DoctorCheck("turn path", CheckStatus.WARN, "the daemon reports ok:false without naming a head")
        else -> DoctorCheck("turn path", CheckStatus.OK, "loopback probes are completing")
    }
}

private const val PERF_TAIL_TURNS = 20
private const val MS_PER_MINUTE = 60_000L
