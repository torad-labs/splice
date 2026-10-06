package splice.head

import splice.core.memory.HeapBudget
import splice.core.memory.HeapReservations
import splice.core.storage.ActivityDays
import splice.core.storage.DayBodyBudget
import splice.core.util.WallClock
import splice.head.wire.TraceStore
import splice.head.wire.TurnIdMint
import splice.upstream.memory.JvmHeap
import java.util.UUID

/** Each fixture owns the same-sized ledger, independent of other tests' retained output and GC timing. */
public fun syntheticHeapBudget(): HeapBudget = HeapBudget(JvmHeap.limitBytes)

/** Synthetic trace fixtures exercise storage regardless of the temporary volume's free-space capacity.
 *  Floor controls inject their own DayBodyBudget and fake volume reader instead. */
public fun syntheticTraceStore(
    days: ActivityDays,
    head: String,
    maxBodyChars: Int,
    now: WallClock = WallClock(System::currentTimeMillis),
    ids: TurnIdMint = TurnIdMint { UUID.randomUUID().toString() },
    heap: HeapReservations = syntheticHeapBudget(),
): TraceStore = TraceStore(
    days,
    head,
    maxBodyChars,
    now,
    ids,
    heap,
    bodyBudget = DayBodyBudget(minFreeBytes = 0, clock = now),
)
