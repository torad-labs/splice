// NEW: the daemon's paired metadata stores and their retention, separated from label decoding.
package splice.sessions.activity

import splice.core.memory.HeapReservations
import splice.core.storage.ActivityDays
import splice.core.storage.DayFiles
import splice.core.util.WallClock
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset

/** The UTC days one local day spans, and the minimum edge window. */
internal const val LOCAL_DAY_UTC_DAYS: Int = 2

/** The console's pair of metadata stores, shared by its publisher and session readers. */
public class ActivityStores(
    activityDir: Path,
    retentionDays: Int,
    storeHeads: String,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    messageEdges: Boolean = true,
    heap: HeapReservations? = null,
) {
    private val edgeDays = retentionDays.coerceAtLeast(LOCAL_DAY_UTC_DAYS)
    public val edges: MessageEdgeStore = MessageEdgeStore(
        ActivityDays(activityDir, EDGES_PREFIX, edgeDays, clock),
        DayFiles(activityDir, EDGES_PREFIX),
        edgeDays,
        messageEdges,
        heap = heap,
    )
    public val activity: ActivityStore = ActivityStore(
        ActivityDays(activityDir, ACTIVITY_PREFIX, LOCAL_DAY_UTC_DAYS, clock),
        ActivityHeads(storeHeads),
        DayFiles(activityDir, ACTIVITY_PREFIX),
    )

    /** UTC start of each store's actual retained window, using its writer's clock. */
    public fun oldestEdgeDay(): Long = oldestDay(edgeDays)
    public fun oldestLabelDay(): Long = oldestDay(LOCAL_DAY_UTC_DAYS)

    private fun oldestDay(days: Int): Long = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC)
        .toLocalDate().minusDays(days.toLong() - 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    public fun edgeState(): KeptState = when {
        edges.deleted() -> KeptState.DELETED
        !edges.storing -> KeptState.OFF
        else -> KeptState.ON
    }

    public fun labelState(): KeptState = when {
        activity.deleted() -> KeptState.DELETED
        !activity.storing() -> KeptState.OFF
        else -> KeptState.ON
    }
}
