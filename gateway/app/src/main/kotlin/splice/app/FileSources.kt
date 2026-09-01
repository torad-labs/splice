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
            renderLines(JsonlSink.readTail(logFile, LOG_TAIL_BYTES), lines)
        }.getOrElse { failure -> unreadable(failure) }
    }

    /** DR-135: the --follow discontinuity re-baseline — [tail]'s text AND the byte offset the
     *  caller must adopt, from ONE read. followPoll used to print [tail] and then adopt a size
     *  STAT taken BEFORE it, so a torn final line (which [tail] withholds) was skipped unprinted
     *  and a line appended between the stat and the read was printed twice. Offset 0 on failure
     *  re-baselines again next poll rather than advancing over unread bytes. */
    public fun tailAt(lines: Int): LogRebase =
        Cancellables.runCatchingCancellable {
            val at = JsonlSink.readTailAt(logFile, LOG_TAIL_BYTES)
            LogRebase(renderLines(at.lines, lines), at.completeEnd)
        }.getOrElse { failure -> LogRebase(unreadable(failure), 0L) }

    /** DR-68 (class law, display flavor): genuine absence stays the quiet empty tail; an
     *  UNREADABLE log degrades to one explicit in-band line instead of a silently blank
     *  dashboard/`splice logs` — the daemon.log itself may be the unreadable file, so the
     *  surface IS the returned text. */
    private fun unreadable(failure: Throwable): String {
        val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
            !Files.exists(logFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        return if (genuinelyAbsent) {
            ""
        } else {
            "[logs unavailable: $logFile unreadable (${SafeFailureText.render(failure)})]"
        }
    }

    /** One definition of the head filter and the line cap, so [tail] and [tailAt] cannot drift. */
    private fun renderLines(lines: List<String>, keep: Int): String =
        lines.asSequence()
            .filter { headTag == null || headTag in it }
            .toList()
            .takeLast(keep.coerceAtMost(MAX_LOG_LINES))
            .joinToString("\n")

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
            val size = ch.size()
            val end = minOf(size, fromOffset + LOG_TAIL_BYTES)
            val want = (end - fromOffset).toInt()
            if (want <= 0) LogDelta("", 0L) else fromWindow(ch, readBytes(ch, fromOffset, want), fromOffset, end, size)
        }

    /** The window holds a newline: ordinary complete lines. It does not, but ended at EOF: the
     *  ordinary torn tail — wait. It does not and there is MORE FILE past it: the line is longer
     *  than one window and can never complete inside it (DR-138). */
    private fun fromWindow(
        ch: java.nio.channels.FileChannel,
        bytes: ByteArray,
        fromOffset: Long,
        end: Long,
        size: Long,
    ): LogDelta {
        val lastNewline = bytes.indexOfLast { it == NEWLINE_BYTE }
        return when {
            lastNewline >= 0 -> completeLines(bytes, lastNewline)
            end < size -> skipOverlongLine(ch, fromOffset, end, size)
            else -> LogDelta("", 0L)
        }
    }

    /** DR-138 (review 2026-08-31): skip the over-long line WHOLE. The first fix consumed just the
     *  window, which left the next poll starting mid-line and emitting the line's tail as a fake
     *  standalone entry — codex measured `[xxxxxxxxxx, after the giant]`. Consuming through the
     *  line's own newline means no fragment can leak. If the file ends with no newline the line is
     *  still being written: consume nothing and wait, which is exactly what a normal torn tail
     *  does, and the follow resumes whole the moment that newline lands. */
    private fun skipOverlongLine(
        ch: java.nio.channels.FileChannel,
        fromOffset: Long,
        end: Long,
        size: Long,
    ): LogDelta {
        val lineEnd = nextLineEnd(ch, end, size) ?: return LogDelta("", 0L)
        val skipped = lineEnd - fromOffset
        val notice = "[log line exceeds $LOG_TAIL_BYTES bytes — skipped $skipped bytes to keep --follow live]"
        return LogDelta(notice, skipped)
    }

    /** The offset just past the first newline at or after [from], or null if [size] arrives first. */
    private fun nextLineEnd(ch: java.nio.channels.FileChannel, from: Long, size: Long): Long? {
        var pos = from
        while (pos < size) {
            val want = minOf(LOG_TAIL_BYTES.toLong(), size - pos).toInt()
            val idx = readBytes(ch, pos, want).indexOfFirst { it == NEWLINE_BYTE }
            if (idx >= 0) return pos + idx + 1
            pos += want
        }
        return null
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

    /** [lastNewline] is the index of the window's final newline — the caller has already
     *  established there is one. '\n' is unambiguous in UTF-8, so the byte split is safe before
     *  decoding. */
    private fun completeLines(bytes: ByteArray, lastNewline: Int): LogDelta {
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

/** A --follow re-baseline: the bounded tail to print and the ABSOLUTE offset to adopt (not a
 *  delta — the discontinuity means the old baseline is meaningless). Both from one read. */
public data class LogRebase(val text: String, val offset: Long)

// LogFileSource's tail bounds. File-scope consts (Kotlin style law, 2026-08-15): a top-level
// `private const val` is the sanctioned home for constants, never a static namespace on the type.
private const val LOG_TAIL_BYTES = 1024 * 1024
private const val MAX_LOG_LINES = 2_000
private const val NEWLINE_BYTE = '\n'.code.toByte()
