// NEW (2026-09-23): the one spelling of an archived perf generation's file name, shared by the head
// that writes it (PerfStats' rotation archive, :features-turns) and the control plane that reads it
// back (PerfRowsFileSource, :app). Two modules, one format: a name the writer changed and the reader
// did not would silently turn every archived generation back into lost history.
//
// The name is the live file's name, a dash, and the UTC second the rotation archived it. That second
// is an UPPER BOUND on every row inside: the archived file is the previous `.1`, whose rows were all
// written before the rotation that retired it. A reader asking for rows since T can therefore skip,
// unopened, any generation archived a full second before T.
package splice.core.perf

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// why: one rotate is rare (64 MB of turns) and per-second is unique enough that two rotates of the
// same file in one process cannot collide on the archived name.
private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

// why: the stamp is floored to the second, so a row written in the same second as the rotation can
// carry a timestamp up to 999 ms past it.
private const val STAMP_RESOLUTION_MS = 1_000L

public class PerfArchiveName(private val liveFile: String) {

    /** The archived name for a generation of [liveFile] retired at [rotatedAtMs]. */
    public fun of(rotatedAtMs: Long): String =
        "$liveFile-${STAMP.format(Instant.ofEpochMilli(rotatedAtMs).atZone(ZoneOffset.UTC))}"

    /** The rotation second (epoch ms) an archived generation of [liveFile] carries in [name], or null
     *  when [name] is not one — another head's archive, a stray file, a hand-renamed copy. */
    public fun rotatedAt(name: String): Long? {
        val stamp = name.removePrefix("$liveFile-").takeIf { it != name } ?: return null
        return try {
            LocalDateTime.parse(stamp, STAMP).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** Whether every row of a generation archived at [rotatedAtMs] is older than [sinceMs]. */
    public fun endsBefore(rotatedAtMs: Long, sinceMs: Long): Boolean = rotatedAtMs + STAMP_RESOLUTION_MS <= sinceMs
}
