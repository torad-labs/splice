package splice.core.perf

import splice.core.turn.Usage
import splice.core.turn.UsageMeasure

/** Additional provenance counters only. TurnBill remains the sole billing/value projection. */
public object UsageMeasurementStamp {
    public val fields: Map<String, UsageMeasure> = linkedMapOf(
        PerfKeys.USAGE_OBSERVED_INPUT to UsageMeasure.INPUT,
        PerfKeys.USAGE_OBSERVED_OUTPUT to UsageMeasure.OUTPUT,
        PerfKeys.USAGE_OBSERVED_CACHE_READ to UsageMeasure.CACHE_READ,
        PerfKeys.USAGE_OBSERVED_CACHE_WRITE to UsageMeasure.CACHE_WRITE,
        PerfKeys.USAGE_OBSERVED_CACHE_WRITE_1H to UsageMeasure.CACHE_WRITE_1H,
    )

    public fun counters(usage: Usage): Map<String, Long> = fields.mapValues {
        if (usage.cutRounds == 0L && it.value in usage.measured) 1L else 0L
    }
}
