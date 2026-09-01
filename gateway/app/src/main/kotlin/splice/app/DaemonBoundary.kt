// PORT-OF: splice/app/Daemon.kt (DaemonBoundary) @ ed5c868 — invariants unchanged: best-effort
// isolation at daemon/head boundaries. A cross-cutting boundary used by call sites across the
// daemon, head boot, control bind and Main's stop timeout, so it stays at root package: Main.kt
// and DaemonTest already reference it unqualified and need no edit for this move.
package splice.app

import splice.core.config.StatePaths
import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.CancellationException

/**
 * Best-effort isolation at daemon/head boundaries without turning cancellation or fatal JVM
 * failures into a merely degraded head. Expected I/O and assembly failures become [Result]
 * failures; cancellation and [Error] always escape.
 *
 * A constructed collaborator rather than a free function (Kotlin style law, 2026-08-15); every
 * user holds one. `inline` is LOAD-BEARING and must stay: six call sites pass a lambda, and the
 * non-local-return semantics plus the absent allocation are part of the boundary's contract.
 */
internal class DaemonBoundary {

    internal inline fun <T> runCatchingDaemonBoundary(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        Result.failure(failure)
    } catch (failure: IllegalArgumentException) {
        Result.failure(failure)
    } catch (failure: IllegalStateException) {
        Result.failure(failure)
    }

    /** The operator-facing reason a boundary-captured failure carries, for the rare throwable with
     *  no message. Named by BRANCH over the three classes [runCatchingDaemonBoundary] can actually
     *  produce — the catch list above IS the closed set — rather than by reflecting on the runtime
     *  class, which is what this used to do. */
    internal fun reason(failure: Throwable): String = failure.message ?: when (failure) {
        is IOException -> "IOException"
        is IllegalArgumentException -> "IllegalArgumentException"
        is IllegalStateException -> "IllegalStateException"
        else -> "boundary failure"
    }

    // Timestamps every log line and tees it to a persistent daemon.log (so failures and slow turns
    // survive restarts and are `tail -f`-able) in addition to stderr. The turn path only enqueues an
    // immutable line; the bounded process-wide file lane owns stderr/filesystem latency. The writer
    // stays OPEN for the daemon's lifetime. A failed write drops the writer so the
    // next line reopens. SIZE-ROTATION (audit 2026-07-18: daemon.log grew forever, ~1KB/turn): at
    // MAX_LOG_BYTES the file is moved to daemon.log.1 (one generation kept) and a fresh file opened.
    //
    // LINE TERMINATION IS NORMALIZED HERE, not trusted to callers (review of PR #62, 2026-07-27).
    // This sink used to `print`/`write` the message verbatim, so a trailing "\n" was part of every
    // caller's string by convention. That convention is invisible at the call site and silently
    // breaks whoever forgets: the kt-no-println conversion moved 14 sites off System.err.println —
    // which appends the newline for you — onto this sink, and their entries merged into one run-on
    // line in daemon.log. /mgmt/logs splits on "\n" (ControlServer.logsJson), so the endpoint this
    // whole change exists to feed emitted concatenated garbage. Exactly one terminator is appended
    // here, which is a no-op for the callers that already pass one and makes the class unrepeatable.
    internal fun persistentLogger(logsDir: Path, maxBytes: Long = MAX_LOG_BYTES): LogSink {
        Cancellables.runCatchingCancellable { Files.createDirectories(logsDir) }
        val file = logsDir.resolve("daemon.log")
        val rolled = logsDir.resolve("daemon.log.1")
        var writer: java.io.Writer? = null
        var written = Cancellables
            .runCatchingCancellable { if (Files.exists(file)) Files.size(file) else 0L }
            .getOrDefault(0L)
        return LogSink { msg ->
            val line = "[${LocalTime.now().truncatedTo(ChronoUnit.SECONDS)}] ${msg.trimEnd('\n')}\n"
            AsyncFileIo.submit {
                System.err.print(line)
                Cancellables.runCatchingCancellable {
                    if (written >= maxBytes) {
                        writer?.close()
                        Files.move(file, rolled, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        writer = null
                        written = 0L
                    }
                    val w = writer ?: Files.newBufferedWriter(file, CREATE, APPEND).also { writer = it }
                    w.write(line)
                    w.flush()
                    written += line.toByteArray(Charsets.UTF_8).size
                }.onFailure { failure ->
                    Cancellables.runCatchingCancellable { writer?.close() }
                    writer = null
                    // SH-14: a failed rotate used to leave `written` >= the cap forever — every later
                    // line re-entered the rotate branch, threw BEFORE reaching newBufferedWriter, and
                    // daemon.log went silent permanently. Reconcile from disk so the next line
                    // self-corrects, and say so on stderr (the one lane still alive here).
                    written = Cancellables.runCatchingCancellable { if (Files.exists(file)) Files.size(file) else 0L }
                        .getOrDefault(0L)
                    System.err.print(
                        "[daemon-log] write/rotate failed (${SafeFailureText.render(failure)}) — " +
                            "size reconciled to $written\n",
                    )
                }
            }
        }
    }

    /** JW-01: the last-resort boot net. Writes SYNCHRONOUSLY — the async file lane is a daemon
     *  thread that dies with the JVM, and this fires when the JVM is dying. No catch clause on the
     *  boot path itself (an uncaught-exception handler needs none), so the cancellation and
     *  broad-catch walls stay untouched. Covers runtime uncaught throwables on other threads too —
     *  a net, not a boot-only special case. */
    internal fun bootFailureHandler(statePaths: StatePaths): Thread.UncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread, e ->
            val line = "[${LocalTime.now().truncatedTo(ChronoUnit.SECONDS)}] [daemon] UNCAUGHT on " +
                "${thread.name}: ${e.stackTraceToString()}\n"
            System.err.print(line)
            Cancellables.runCatchingCancellable {
                Files.createDirectories(statePaths.logsDir)
                Files.writeString(statePaths.logsDir.resolve("daemon.log"), line, CREATE, APPEND)
            }
        }

    // Port→pid lookup that used to live on DaemonStop. The stop ladder still decides
    // WHEN to signal; this type already owns process-boundary isolation, so the ss
    // scoped lookup sits here rather than adding a third file.
    internal fun daemonOnPort(port: Int): ProcessHandle? = pidsOnPort(port)
        .firstNotNullOfOrNull { pid ->
            ProcessHandle.of(pid).orElse(null)?.takeIf { ph ->
                val cmd = ph.info().commandLine().orElse("")
                cmd.contains("daemon") && (cmd.contains("splice.jar") || cmd.contains("app-all.jar"))
            }
        }

    internal fun pidsOnPort(port: Int): List<Long> = Cancellables.runCatchingCancellable {
        ProcessBuilder("ss", "-ltnpH", "( sport = :$port )").redirectErrorStream(true).start()
            .inputStream.bufferedReader().use { it.readText() }
            .let { Regex("pid=(\\d+)").findAll(it).map { m -> m.groupValues[1].toLong() }.toList() }
    }.getOrDefault(emptyList())
}

// One rolled generation at 64MB caps daemon.log disk at ~128MB — plenty of tail history, bounded.
private const val MAX_LOG_BYTES = 64L * 1024 * 1024
