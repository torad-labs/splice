// NEW: the head's latest quota windows — file truth plus an in-memory copy — fed from two sides:
// upstream response headers observed on a round (Anthropic's unified family on a passthrough head,
// the x-codex family on a Codex round) and the provider usage endpoints the app-side poller
// probes. Read by every client response (the unified headers Claude Code draws its bars from) and
// by the control plane (statusline, /api/usage).
package splice.gateway.usage

import splice.core.usage.QuotaHeaders
import splice.core.usage.QuotaJson
import splice.core.usage.QuotaSnapshot
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

public class QuotaTracker(
    private val file: Path,
    clock: WallClock = WallClock(System::currentTimeMillis),
    private val log: LogSink = LogSink(DaemonLog::write),
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
        headers.fromUpstream { name -> header(name) }?.let(::record)
    }

    /** What every client response carries so Claude Code's rate_limits show this head's windows. */
    public fun clientHeaders(): Map<String, String> = latest.get()?.let(headers::forClient).orEmpty()

    private fun readFile(): QuotaSnapshot? =
        Cancellables.runCatchingCancellable { codec.decode(Files.readString(file)) }.getOrNull()
}
