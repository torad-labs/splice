// NEW: gpt-speed (docs/specs/gpt-speed.md, decision 5) — a turn's speed on its perf row, both ways: the speed its
// request asked for (the body's `service_tier`, none at standard) and the speed the backend says it served it at (the
// response's `service_tier`), so a turn served slower than asked shows it, and is priced as served.
package splice.core.perf

import kotlinx.serialization.json.JsonObject
import splice.core.speed.SERVICE_TIER
import splice.core.speed.Speed
import splice.core.speed.SpeedWords
import splice.core.turn.Usage
import splice.core.util.JsonScalars

/** The one mapping between a turn's speed and its perf row's counters. */
public object SpeedCounters {
    /** The counters a turn whose request was [requestBody] and whose bill is [usage] writes. None when the backend
     *  named no tier splice has a word for, which says nothing about speed, so the row claims none. */
    public fun counters(requestBody: JsonObject?, usage: Usage?): Map<String, Long> {
        val served = SpeedWords.served(usage?.servedTier) ?: return emptyMap()
        val asked = SpeedWords.served(requestBody?.let { JsonScalars.str(it, SERVICE_TIER) }) ?: Speed.STANDARD
        return mapOf(PerfKeys.SPEED_ASKED to asked.code, PerfKeys.SPEED to served.code)
    }

    /** The speed [row]'s request asked for, or null on a row that names none. */
    public fun asked(row: Map<String, Long>): Speed? = of(row[PerfKeys.SPEED_ASKED])

    /** The speed [row] was served at, or null on a row that names none. */
    public fun served(row: Map<String, Long>): Speed? = of(row[PerfKeys.SPEED])

    private fun of(code: Long?): Speed? = code?.let { c -> Speed.entries.firstOrNull { it.code == c } }
}
