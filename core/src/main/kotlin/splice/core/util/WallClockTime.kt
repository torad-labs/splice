// NEW: failure-handling slice 5 (2026-10-27). A held turn's line names when it continues ("in 9 minutes", "until
// 9:00 AM") from the head's own wall clock, the one its hold waits on, so the line and the wait agree on what now is.
package splice.core.util

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** [wall] read as a java.time clock in [zone]. */
public class WallClockTime(private val wall: WallClock, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = WallClockTime(wall, zone)

    override fun instant(): Instant = Instant.ofEpochMilli(wall())

    override fun millis(): Long = wall()
}
