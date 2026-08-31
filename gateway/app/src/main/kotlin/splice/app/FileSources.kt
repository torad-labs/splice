// NEW: adapters bridging the gateway's file stores to the control plane's read interfaces, so
// the dashboard reads the same on-disk truth the head writes (a DOWN head still shows state).
package splice.app

import splice.control.CompactView
import splice.control.HeadCompactSource
import splice.control.HeadLogSource
import splice.control.HeadPerfSource
import splice.control.HeadUsageSource
import splice.control.RateLimitView
import splice.control.UsageView
import splice.core.util.Cancellables
import splice.core.util.JsonlSink
import splice.core.util.SafeFailureText
import splice.gateway.compact.CompactStats
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import java.nio.file.Files
import java.nio.file.Path

public class UsageStoreSource(private val store: UsageStore) : HeadUsageSource {
    override fun snapshot(): UsageView {
        val state = store.readState()
        val ratelimit = store.readRateLimit()?.let {
            RateLimitView(it.limitTokens, it.remainingTokens, it.resetTokens)
        }
        return UsageView(state.outputTokens5h, state.entries, ratelimit)
    }
}

public class CompactStatsSource(private val stats: CompactStats) : HeadCompactSource {
    override fun summary(tailN: Int): CompactView {
        val s = stats.read(tailN)
        val tail = s.tail.map { row -> row.mapValues { (_, v) -> v.toString() } }
        return CompactView(s.total, s.byOutcome, tail)
    }
}

public class PerfStatsSource(private val stats: PerfStats) : HeadPerfSource {
    override fun tailNumeric(n: Int): List<Map<String, Long>> = stats.tailNumeric(n)
}

public class LogFileSource(
    private val logFile: Path,
    private val headTag: String? = null,
) : HeadLogSource {
    // DR-68 (class law, display flavor): genuine absence stays the quiet empty tail; an
    // UNREADABLE log degrades to one explicit in-band line instead of a silently blank
    // dashboard/`splice logs` — the daemon.log itself may be the unreadable file, so the
    // surface IS the returned text.
    override fun tail(lines: Int): String {
        if (lines <= 0) return ""
        return Cancellables.runCatchingCancellable {
            JsonlSink.readTail(logFile, LOG_TAIL_BYTES)
                .asSequence()
                .filter { headTag == null || headTag in it }
                .toList()
                .takeLast(lines.coerceAtMost(MAX_LOG_LINES))
                .joinToString("\n")
        }.getOrElse { failure ->
            val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                !Files.exists(logFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            if (genuinelyAbsent) "" else "[logs unavailable: $logFile unreadable (${SafeFailureText.render(failure)})]"
        }
    }

    override fun path(): String = logFile.toString()
}

// LogFileSource's tail bounds. File-scope consts (Kotlin style law, 2026-08-15): a top-level
// `private const val` is the sanctioned home for constants, never a static namespace on the type.
private const val LOG_TAIL_BYTES = 1024 * 1024
private const val MAX_LOG_LINES = 2_000
