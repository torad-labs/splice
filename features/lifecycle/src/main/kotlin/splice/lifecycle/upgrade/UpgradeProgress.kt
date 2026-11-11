// NEW (2026-11-10): what `splice upgrade` prints while a release asset downloads. The 0.4.0 jar is about 473 MB, minutes
// on an ordinary connection, and a command that says nothing for that long looks hung. One line names the size when the
// download starts, one every half minute says how far it got, and a resume or a fresh start is said as it happens. The
// lines go where the rest of the upgrade's go, so the console's run view (UpgradeRuns) shows them too.
package splice.lifecycle.upgrade

import splice.core.terminal.TerminalOutput
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import splice.core.wire.HttpStatus
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.util.Locale
import javax.net.ssl.SSLException

// why: the sums and the launch shim are a few kilobytes and arrive at once; a line about them is noise.
private const val PROGRESS_FLOOR_BYTES = 1_000_000L

// why: often enough that a person (or the console's run view) sees it move, rarely enough that a half-hour download
// stays well inside the 200 lines the console keeps.
private const val PROGRESS_EVERY_MS = 30_000L

// why: a release's size is said in decimal megabytes, as the release page and a browser's download say it.
private const val BYTES_PER_DECIMAL_MB = 1_000_000.0

/** The words a fetch failure and a progress line are made of. */
internal class FetchWords {
    fun size(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / BYTES_PER_DECIMAL_MB)

    fun status(status: Int): String = when {
        status == HttpStatus.FORBIDDEN -> "forbidden"
        status == HttpStatus.TOO_MANY_REQUESTS -> "rate limited, retry later"
        status >= HttpStatus.INTERNAL_SERVER_ERROR -> "server error, retry later"
        else -> "refused"
    }

    fun transport(e: Throwable?): String = when (e) {
        is UnknownHostException -> "host not found (DNS)"
        is ConnectException -> "connection refused"
        is HttpConnectTimeoutException -> "the connection timed out"
        is SSLException -> "TLS handshake failed"
        is IOException -> "connection failed"
        else -> "request failed"
    }
}

internal class ProgressLines(
    private val output: TerminalOutput,
    private val asset: String,
    private val clock: ElapsedClock = ElapsedClock { MonoClock.nowMs() },
) : DownloadProgress {
    private val words = FetchWords()
    private var saidAt: Long? = null

    override fun received(done: Long, total: Long?) {
        if (total == null || total < PROGRESS_FLOOR_BYTES) return
        val now = clock()
        val last = saidAt
        val text = when {
            last == null -> "downloading ${words.size(total)}"
            now - last >= PROGRESS_EVERY_MS && done < total ->
                "${words.size(done)} of ${words.size(total)} (${done * 100 / total}%)"
            else -> null
        }
        if (text != null) {
            line(text)
            saidAt = now
        }
    }

    override fun note(line: String) = line(line)

    private fun line(text: String) = output.line("    ${asset.padEnd(UPGRADE_PAD)} $text")
}
