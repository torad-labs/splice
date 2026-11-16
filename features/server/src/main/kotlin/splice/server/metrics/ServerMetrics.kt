// NEW: server mode (docs/specs/server-mode.md, F6). Each person's traffic, live, as whoever runs the box watches it:
// what the server counted of their requests since the daemon started (TurnTallies), and what they hold in flight now
// against their cap. Only people with a live key have series, and each of them has every series from startup, at zero
// until they send something; a person whose keys are all revoked or expired has none. An admin scrapes it from the
// listener as Prometheus text, and the operator's OTLP export reads the same snapshot.
package splice.server.metrics

import splice.core.server.MetricFamily
import splice.core.server.MetricLabels
import splice.core.server.MetricPoint
import splice.core.server.MetricSnapshot
import splice.core.server.ServedMetricSource
import splice.core.topology.ServerTeams
import splice.server.PersonLimits
import splice.server.ServerKeys
import splice.server.ServerPeople
import splice.server.ledger.LedgerRow
import splice.share.SharedHeads
import java.time.Clock

/** Whose metrics are kept and how each one reads now: the keys that say who is live, each person's team and caps. */
internal data class MetricPeople(
    val keys: ServerKeys,
    val people: ServerPeople,
    val teams: ServerTeams,
    val limits: PersonLimits,
)

public class ServerMetrics internal constructor(private val who: MetricPeople, private val clock: Clock) :
    ServedMetricSource {
    private val sinceMs = clock.millis()
    private val tallies = TurnTallies()
    private val exposition = PrometheusExposition()

    // The heads the listener serves, once it opened: a person's request series start at zero for each command.
    @Volatile
    private var heads: SharedHeads? = null

    /** The listener opened, serving [heads]. */
    internal fun serving(heads: SharedHeads) {
        this.heads = heads
    }

    /** A request ended as [row] says, its streamed answer having stalled [stallMs] at most after its first word. */
    internal fun counted(row: LedgerRow, stallMs: Long?) {
        tallies.counted(row, stallMs)
    }

    override fun snapshot(): MetricSnapshot {
        val live = who.keys.livePeople()
        val commands = heads?.offered().orEmpty().map { it.command }.distinct()
        live.forEach { person -> tallies.present(person, who.people.team(person, who.teams).orEmpty(), commands) }
        return MetricSnapshot(sinceMs, clock.millis(), tallies.families(live) + gauges(live))
    }

    /** The snapshot as an admin's scrape reads it. */
    internal fun prometheus(): String = exposition.render(snapshot())

    /** What each person in [live] holds in flight now, and the most they may: `+Inf` where nothing caps them. */
    private fun gauges(live: Set<String>): List<MetricFamily> {
        val held = live.map { MetricPoint(person(it), who.limits.inflight(it).toDouble()) }
        val caps = live.map { person ->
            MetricPoint(person(person), who.limits.inflightCap(person)?.toDouble() ?: Double.POSITIVE_INFINITY)
        }
        return listOf(
            MetricFamily.Gauge(INFLIGHT_METRIC, INFLIGHT_HELP, UNIT_REQUESTS, held),
            MetricFamily.Gauge(INFLIGHT_LIMIT, INFLIGHT_LIMIT_HELP, UNIT_REQUESTS, caps),
        )
    }

    private fun person(person: String) = MetricLabels(listOf(LABEL_PERSON to person))
}

private const val INFLIGHT_METRIC = "splice_server_inflight"
private const val INFLIGHT_HELP = "Requests the person has in flight now."
private const val INFLIGHT_LIMIT = "splice_server_inflight_limit"
private const val INFLIGHT_LIMIT_HELP =
    "The most requests the person may have in flight at once (their own cap, else inflight_per_person); +Inf where " +
        "nothing caps them."
