package splice.server.ledger

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.JsonlSink
import java.nio.file.Files
import java.nio.file.Path

@Serializable
internal data class MeterInterval(
    val meter: String,
    @SerialName("from_ms") val from: Long,
    @SerialName("to_ms") val to: Long,
    @SerialName("hourly_usd") val hourlyUsd: Double,
) {
    fun hoursIn(window: UsageWindow): Double {
        val start = maxOf(from, window.from.toEpochMilli())
        val end = minOf(to, window.to.toEpochMilli())
        return if (end > start) (end - start) / MILLIS_PER_HOUR else 0.0
    }
}

/** A journal of confirmed intervals, priced at the rate in force when each was observed. */
internal class MeterIntervals(private val file: Path) {
    private val intervals = linkedMapOf<String, MutableList<MeterInterval>>()

    init {
        if (Files.exists(file)) {
            Files.newBufferedReader(file).useLines { lines -> lines.mapNotNull(::interval).forEach(::remember) }
        }
    }

    private fun interval(line: String): MeterInterval? = try {
        Json.decodeFromString<MeterInterval>(line).takeIf {
            it.from < it.to && it.hourlyUsd.isFinite() && it.hourlyUsd >= 0
        }
    } catch (_: IllegalArgumentException) {
        // A torn line is not a confirmed interval. Later complete intervals still count.
        null
    }

    @Synchronized
    fun recordedThrough(meter: String): Long? = intervals[meter]?.maxOfOrNull { it.to }

    fun add(interval: MeterInterval) {
        JsonlSink.appendLine(file, Json.encodeToString(interval), Long.MAX_VALUE)
        remember(interval)
    }

    // A continuous run at one rate occupies one range in memory, even though every completed poll is durable.
    @Synchronized
    private fun remember(interval: MeterInterval) {
        val meter = intervals.getOrPut(interval.meter) { mutableListOf() }
        val last = meter.lastOrNull()?.takeIf { it.to == interval.from && it.hourlyUsd == interval.hourlyUsd }
        if (last == null) {
            meter.add(interval)
        } else {
            meter[meter.lastIndex] = last.copy(to = interval.to)
        }
    }

    @Synchronized
    fun costs(names: Set<String>, window: UsageWindow): List<FixedCost> =
        (names + intervals.keys).mapNotNull { name ->
            val cost = costOf(name, window)
            cost.takeIf { name in names || cost.hours > 0 }
        }

    private fun costOf(name: String, window: UsageWindow): FixedCost {
        var hours = 0.0
        var cost = 0.0
        for (interval in intervals[name].orEmpty()) {
            val overlap = interval.hoursIn(window)
            hours += overlap
            cost += overlap * interval.hourlyUsd
        }
        return FixedCost(name, hours, cost, external = true)
    }
}

internal const val COST_METER_FILE = "cost-meters.jsonl"

// why: intervals are stamped in epoch milliseconds and rates are per hour; a Double so the division keeps fractions.
private const val MILLIS_PER_HOUR = 3_600_000.0
