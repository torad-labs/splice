// NEW: V4-419 — the ONE rule for a time a person reads in product text: the machine's own zone, said with
// that zone's abbreviation ("Oct 4, 7:00 PM CDT"). Before it `splice status` hard-coded America/Chicago and
// printed " CT" to every user, and the trace sentence for a spent plan needed the same instant said the same
// way. In core so the trace (features/turns) and the CLI (app) read one declaration; a third place that
// formats a reset for a person builds this type, it does not spell a zone.
//
// Not for the wire: a file or a header reads the ISO instant or epoch its consumer expects, and this type
// never touches those. A sentence a person reads is another matter, so the refusal a client prints for a
// spent plan window (PlanLimit.refusal, V4-425) says its reset through this type too.
package splice.core.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Says an epoch instant as month, day and clock time in [zone], ending with the zone's own abbreviation.
 *  [zone] defaults to the machine's, which is the only value production passes; tests name one. */
public class LocalTimeText(zone: ZoneId = ZoneId.systemDefault()) {
    private val format = DateTimeFormatter.ofPattern("MMM d, h:mm a z", Locale.US).withZone(zone)

    /** [epochSeconds] as "Oct 4, 7:00 PM CDT" in this rule's zone. */
    public fun at(epochSeconds: Long): String = format.format(Instant.ofEpochSecond(epochSeconds))
}
