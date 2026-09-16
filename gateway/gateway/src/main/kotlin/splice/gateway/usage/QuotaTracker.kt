// NEW: the head's latest quota windows — file truth plus an in-memory copy — fed from two sides:
// upstream response headers observed on a round (Anthropic's unified family on a passthrough head,
// the x-codex family on a Codex round) and the provider usage endpoints the app-side poller
// probes. Read by every client response (the unified headers Claude Code draws its bars from) and
// by the control plane (statusline, /api/usage).
package splice.gateway.usage

import splice.core.usage.QuotaHeaderRead
import splice.core.usage.QuotaHeaders
import splice.core.usage.QuotaJson
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaStatus
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import splice.spi.QuotaHeaderFamily
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

public class QuotaTracker(
    private val file: Path,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val log: LogSink = LogSink(DaemonLog::write),
    private val extraFamily: QuotaHeaderFamily? = null,
) {
    private val codec = QuotaJson()
    private val headers = QuotaHeaders(clock)
    private val latest = AtomicReference<QuotaSnapshot?>(readFile())

    public fun snapshot(): QuotaSnapshot? = latest.get()

    /** Latest wins. Persisted at once: a snapshot arrives at most once per round or per poll. */
    public fun record(snapshot: QuotaSnapshot) {
        if (snapshot.isEmpty) return
        latest.set(snapshot)
        Cancellables.runCatchingCancellable { SecureFile.writeAtomic0600(file, codec.encode(snapshot)) }
            .onFailure { log("[quota] $file write FAILED (${SafeFailureText.render(it)})\n") }
    }

    /** Upstream response headers of the round that just completed. A no-op for the common case
     *  of an upstream that sends neither family. */
    public fun observe(header: HeaderLookup) {
        val read = QuotaHeaderRead { name -> header(name) }
        (headers.fromUpstream(read) ?: extraFamily?.snapshot(read, clock))?.let(::record)
    }

    /** What every client response carries so Claude Code's rate_limits show this head's windows. */
    public fun clientHeaders(): Map<String, String> = latest.get()?.let(headers::forClient).orEmpty()

    /** V4-51: the REFUSAL variant, for the admission-side 429 V4-50 sends. Same family, with
     *  `-status: rejected` and the plain `anthropic-ratelimit-unified-reset` naming
     *  [resetEpochSeconds] — the member Claude Code's withRetry reads off a 429 to decide WHEN to
     *  come back, as opposed to how full a bucket is.
     *
     *  A head with no tracked snapshot still states the refusal, because the deadline is the whole
     *  message and the window members are optional; [resetEpochSeconds] null simply omits the
     *  deadline rather than inventing one. */
    public fun clientHeadersRejected(resetEpochSeconds: Long?): Map<String, String> =
        headers.forClient(latest.get() ?: QuotaSnapshot(), QuotaStatus.REJECTED, resetEpochSeconds)

    private fun readFile(): QuotaSnapshot? =
        Cancellables.runCatchingCancellable { codec.decode(Files.readString(file)) }.getOrNull()
}
