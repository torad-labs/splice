// NEW: Oct 13, 2026 — a turn's perf row read back from the JSONL that headStores writes. The row is the turn's
// record: the `[head] perf outcome=` daemon.log line that tests once waited on left the log, since it repeated
// this row whole.
package splice.head

import splice.core.util.AsyncFileIo
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

private const val POLL_MS = 20L

/** The rows in [file] once every pending asynchronous append has landed; none when the file does not exist yet. */
public fun perfRows(file: Path): List<String> {
    AsyncFileIo.drain()
    return if (Files.exists(file)) Files.readAllLines(file).filter { it.isNotBlank() } else emptyList()
}

/** The newest row after the first [from] that matches [match], waiting up to [timeoutMs]: a turn's row is written on
 *  a server thread after the response ends, with no signal to await. Null when none landed in time. */
public fun awaitPerfRow(file: Path, from: Int = 0, timeoutMs: Long = 30_000, match: (String) -> Boolean): String? {
    val deadline = System.currentTimeMillis() + timeoutMs
    var found = perfRows(file).drop(from).lastOrNull(match)
    while (found == null && System.currentTimeMillis() < deadline) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_MS))
        found = perfRows(file).drop(from).lastOrNull(match)
    }
    return found
}
