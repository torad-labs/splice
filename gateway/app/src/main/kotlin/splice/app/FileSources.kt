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

    /** DR-100: the --follow delta read — complete lines from [fromOffset] to EOF (bounded at
     *  LOG_TAIL_BYTES per call), head-filtered like [tail]. [LogDelta.consumed] is the bytes up
     *  to and including the last newline — a torn final line stays unconsumed so the caller's
     *  baseline never advances past unprinted bytes. Read failures return the quiet zero delta:
     *  the follow loop's own polledSize warning owns the unreadable-episode surface (DR-68). */
    public fun readFrom(fromOffset: Long): LogDelta =
        Cancellables.runCatchingCancellable { readDelta(fromOffset) }.getOrElse { LogDelta("", 0L) }

    private fun readDelta(fromOffset: Long): LogDelta =
        java.nio.channels.FileChannel.open(logFile, java.nio.file.StandardOpenOption.READ).use { ch ->
            val end = minOf(ch.size(), fromOffset + LOG_TAIL_BYTES)
            val want = (end - fromOffset).toInt()
            if (want <= 0) LogDelta("", 0L) else completeLines(readBytes(ch, fromOffset, want))
        }

    private fun readBytes(ch: java.nio.channels.FileChannel, from: Long, want: Int): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(want)
        ch.position(from)
        var more = true
        while (buf.hasRemaining() && more) {
            more = ch.read(buf) >= 0
        }
        buf.flip()
        return ByteArray(buf.remaining()).also { buf.get(it) }
    }

    private fun completeLines(bytes: ByteArray): LogDelta {
        // '\n' is unambiguous in UTF-8, so the byte split is safe before decoding.
        val lastNewline = bytes.indexOfLast { it == NEWLINE_BYTE }
        if (lastNewline < 0) return LogDelta("", 0L)
        val text = String(bytes, 0, lastNewline, Charsets.UTF_8)
            .lineSequence()
            .filter { headTag == null || headTag in it }
            .joinToString("\n")
        return LogDelta(text, (lastNewline + 1).toLong())
    }
}

/** One --follow delta: the filtered complete lines to print (may be empty when every new line was
 *  another head's) and the raw bytes consumed — the caller advances its baseline by [consumed]. */
public data class LogDelta(val text: String, val consumed: Long)

// LogFileSource's tail bounds. File-scope consts (Kotlin style law, 2026-08-15): a top-level
// `private const val` is the sanctioned home for constants, never a static namespace on the type.
private const val LOG_TAIL_BYTES = 1024 * 1024
private const val MAX_LOG_LINES = 2_000
private const val NEWLINE_BYTE = '\n'.code.toByte()
