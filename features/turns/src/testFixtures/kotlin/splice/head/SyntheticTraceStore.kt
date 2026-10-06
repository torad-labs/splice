package splice.head

import splice.core.memory.HeapBudget
import splice.core.storage.ActivityDays
import splice.core.storage.DayBodyBudget
import splice.core.util.WallClock
import splice.head.wire.TraceStore
import splice.head.wire.TurnIdMint
import splice.upstream.memory.JvmHeap
import java.util.UUID

/** Synthetic trace fixtures exercise storage regardless of the temporary volume's free-space capacity.
 *  Floor controls inject their own DayBodyBudget and fake volume reader instead. */
public fun syntheticTraceStore(
    days: ActivityDays,
    head: String,
    maxBodyChars: Int,
    now: WallClock = WallClock(System::currentTimeMillis),
    ids: TurnIdMint = TurnIdMint { UUID.randomUUID().toString() },
    heap: HeapBudget = JvmHeap.budget,
): TraceStore = TraceStore(
    days,
    head,
    maxBodyChars,
    now,
    ids,
    heap,
    bodyBudget = DayBodyBudget(minFreeBytes = 0, clock = now),
)
