package splice.usage.perf.history

import splice.core.perf.observation.PerfObservation

/** Optional bounded reduction boundary; a source may also deliver the entire population as one partition. */
public interface PerfPartitionedObservationConsumer : PerfObservationConsumer {
    /** Every event for each assigned (boot, request) has been delivered from the same frozen source prefix. */
    public fun partitionFinished()
}

/** Partition selection happens after global observation deduplication and payload-conflict validation. */
public object PerfRequestPartitions {
    // Four passes bound scalar request state without expanding the source's cumulative work/time or disk limits.
    public const val COUNT: Int = 4

    public fun matches(event: PerfObservation, partition: Int): Boolean {
        val request = event.links.requestId ?: return partition == 0
        val hash = event.stamp.identity.bootId.hashCode() xor request.hashCode()
        return Math.floorMod(hash, COUNT) == partition
    }
}
