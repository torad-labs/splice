// NEW: (JW-08, no Node source) `splice logs [--head <key>] [--tail N] [--follow]`. Every
// remediation path ends at daemon.log, but until now there was no CLI verb to reach it and the
// dashboard panel — the only surface — needs the daemon up, a browser, and the mgmt-key, exactly
// what is broken when you need logs. This reuses LogFileSource (a pure byte-bounded Files read +
// head-tag filter), so it works with the daemon STOPPED: no HTTP, no key. :app: println is fine.
package splice.app.cli

import splice.app.LogFileSource
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import java.nio.file.Files

/** The `logs` verb as a cohesive unit of behavior (Kotlin style law, 2026-08-15: main sources
 *  carry no top-level functions). Every member keeps the old function's name. */
public class LogsCommand {

    public fun logs(args: List<String>, envReader: EnvReader = EnvReader(System::getenv)): Boolean {
        val opts = parseLogsArgs(args) ?: return false
        val statePaths = StatePaths(envReader = envReader)
        val logFile = statePaths.logsDir.resolve("daemon.log")
        val source = LogFileSource(logFile, opts.head?.let { "[$it]" })

        // Missing file is not an error (a fresh install has no turns yet) — empty output, exit 0.
        val head = source.tail(opts.tail)
        if (head.isNotEmpty()) println(head)
        if (!opts.follow) return true
        followTail(logFile, source)
        return true
    }

    /** --follow: poll file size; reopen on shrink so a rotation (daemon.log -> daemon.log.1) does
     *  not silently freeze the tail (Main.kt's one-generation roll). Head filtering rides through
     *  LogFileSource on every poll, so a followed per-head view stays filtered across a roll. */
    private fun followTail(logFile: java.nio.file.Path, source: LogFileSource) {
        var lastSize = runCatching { Files.size(logFile) }.getOrDefault(0L)
        // DR-68: a follow that cannot STAT the file must say so once per episode instead of
        // freezing silently; genuine absence (rotation gap) stays the quiet 0.
        val statWarned = java.util.concurrent.atomic.AtomicBoolean(false)
        while (true) {
            Thread.sleep(FOLLOW_POLL_MS)
            lastSize = followPoll(logFile, source, lastSize, statWarned)
        }
    }

    /** One --follow poll: print what changed since [lastSize], return the new baseline.
     *  `internal` so the poll step is testable without the infinite loop around it. */
    internal fun followPoll(
        logFile: java.nio.file.Path,
        source: LogFileSource,
        lastSize: Long,
        statWarned: java.util.concurrent.atomic.AtomicBoolean,
    ): Long {
        val size = polledSize(logFile, statWarned)
        if (size == lastSize) return lastSize
        // A shrink is a roll (daemon.log -> .1: new generation, old offsets meaningless) and a
        // zero baseline is a fresh or unreadable-recovered start — both re-baseline with the
        // bounded tail, the only safe reset across a discontinuity.
        val discontinuity = size < lastSize || lastSize == 0L
        if (discontinuity) {
            val fresh = source.tail(FOLLOW_TAIL)
            if (fresh.isNotEmpty()) println(fresh)
            return size
        }
        // DR-100: ordinary growth prints exactly the NEW bytes' complete lines. The old 20-line
        // snapshot repeated up to 19 already-shown lines per new line and silently dropped any
        // burst over 20 lines inside one poll — exactly the error-storm lines being watched.
        val delta = source.readFrom(lastSize)
        if (delta.text.isNotEmpty()) println(delta.text)
        return lastSize + delta.consumed
    }

    /** One poll's size, with DR-68's once-per-episode unreadable warning (the DR-63 latch idiom:
     *  ANY healthy stat re-arms — a zero-byte file is a healthy read, not a continuing episode
     *  (DR-68 redo; `size > 0` was the DR-63 isNotEmpty scar reintroduced). Proven absence
     *  re-arms too (the RateLimitFile sibling); only indeterminate access holds the latch. */
    internal fun polledSize(logFile: java.nio.file.Path, warned: java.util.concurrent.atomic.AtomicBoolean): Long =
        Cancellables.runCatchingCancellable { Files.size(logFile) }
            .onSuccess { warned.set(false) }
            .getOrElse { failure ->
                val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                    !Files.exists(logFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                if (genuinelyAbsent) {
                    warned.set(false)
                } else if (warned.compareAndSet(false, true)) {
                    println("splice: $logFile unreadable (${SafeFailureText.render(failure)}) — still polling")
                }
                0L
            }

    private fun parseLogsArgs(args: List<String>): LogsOpts? {
        val opts = LogsOpts()
        val flags = args.filter { it == "--follow" || it == "-f" }
        opts.follow = flags.isNotEmpty()
        val valued = args.filterNot { it == "--follow" || it == "-f" }
        val error = applyValuedLogsArgs(valued, opts)
        if (error != null) {
            System.err.println("splice logs: $error\nusage: splice logs [--head <key>] [--tail N] [--follow|-f]")
            return null
        }
        return opts
    }

    /** Consumes the value-taking options (--head/--tail) as (flag, value) pairs; returns an error
     *  string or null. Flags were stripped by the caller, so every entry here must pair. */
    private fun applyValuedLogsArgs(valued: List<String>, opts: LogsOpts): String? {
        var i = 0
        var error: String? = null
        while (i < valued.size && error == null) {
            val flag = valued[i]
            val value = valued.getOrNull(i + 1)
            error = when {
                value == null -> "$flag needs a value"
                flag == "--head" -> {
                    opts.head = value
                    null
                }
                flag == "--tail" -> applyTail(value, opts)
                else -> "unknown option '$flag'"
            }
            i += 2
        }
        return error
    }

    private fun applyTail(value: String, opts: LogsOpts): String? {
        val n = value.toIntOrNull()?.takeIf { it > 0 } ?: return "--tail needs a positive integer"
        opts.tail = n
        return null
    }
}

private data class LogsOpts(
    var head: String? = null,
    var tail: Int = DEFAULT_TAIL,
    var follow: Boolean = false,
)

private const val DEFAULT_TAIL = 50
private const val FOLLOW_TAIL = 20
private const val FOLLOW_POLL_MS = 500L
