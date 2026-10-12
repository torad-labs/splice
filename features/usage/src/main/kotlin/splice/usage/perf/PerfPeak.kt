// NEW: V4-444 — the most requests a command held at once, over every row of a window.
package splice.usage.perf

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.PerfKeys

/**
 * The most requests a command held at once, and when, over every one of [rows].
 *
 * Settings drew this in the browser from the newest 2,000 rows of the day, so a busy command, whose day always holds more,
 * could never be read. The daemon holds the whole window, so it answers here. Each request held a slot from the start of its
 * sending (its end minus its time, plus its wait in line) to its end. Where one ends the moment another starts, the end
 * counts first, so two back-to-back requests are one at a time and never two.
 */
internal class PerfPeak(private val rows: List<PerfRow>) {
    /** The peak, the first instant it was reached, the requests counted and how many of them the gate HELD because the
     *  limit was full (ADMIT_HELD_MS). A pass through an open gate records a wait of a millisecond or so and is not
     *  waiting: on his Oct 12 day 1,151 rows carried such a wait under a peak of 33 and a limit of 100 (Marlin). */
    private data class Reading(val peak: Int, val at: Long, val counted: Int, val queued: Int)

    private fun read(): Reading {
        val held = rows.flatMap { row ->
            val waited = row.fields[PerfKeys.ADMIT_WAIT_MS] ?: 0L
            val started = row.ts - (row.fields[PerfKeys.TOTAL] ?: 0L) + waited
            listOf(Slot(started, 1), Slot(row.ts, -1))
        }.sortedWith(compareBy<Slot> { it.at }.thenBy { it.step })
        var open = 0
        var peak = 0
        var at = 0L
        for (slot in held) {
            open += slot.step
            if (open > peak) {
                peak = open
                at = slot.at
            }
        }
        return Reading(peak, at, rows.size, rows.count { PerfKeys.ADMIT_HELD_MS in it.fields })
    }

    private data class Slot(val at: Long, val step: Int)

    fun json(): JsonObject {
        val reading = read()
        return buildJsonObject {
            put("peak", reading.peak)
            put("at", reading.at)
            put("counted", reading.counted)
            put("queued", reading.queued)
        }
    }
}
