// NEW: jar pick, ProcessBuilder spawn, and boot-log tail for CLI cold-start.
// Split from DaemonLaunch.kt (concentration HIGH, 2026-08-19). The argv
// string that names daemon-boot.log stays on DaemonLaunch so JW-01 keeps
// its path-anchored token.
package splice.app.cli

import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path

internal class DaemonSpawn(private val health: DaemonHealth) {

    internal fun logsDir(): Path = StatePaths().logsDir

    /** The jar to (re)launch from, or null (with a printed reason) when cold-start must be REFUSED.
     *  Waits a short bounded window for the control port to free first: spawning while a prior daemon
     *  still holds it (stopped answering /health but not yet exited — BS-4 DEFECT B) would let the new
     *  daemon win the just-released lock and then die on the uncaught control bind, leaving zero serving. */
    internal fun startableJar(port: Int): Path? {
        var polls = PORT_FREE_POLLS
        while (health.controlPortBound(port) && polls-- > 0) Thread.sleep(POLL_INTERVAL_MS)
        if (health.controlPortBound(port)) {
            println("splice: control port $port is still bound (a daemon is still shutting down) — retry in a moment")
            return null
        }
        val jar = AdminSupport.selfJar()
        if (jar == null) println("splice: can't find the splice jar to start the daemon (run: splice install).")
        return jar
    }

    /** Spawn the detached daemon process; false (with a message) if it can't be launched.
     *  JVM opts ride $SPLICE_JVM_OPTS inside the argv the caller built — expanded BY THE SHELL so
     *  the wall keeping System.getenv out of non-config code stays intact. */
    internal fun spawnDaemon(argv: List<String>): Boolean =
        Cancellables.runCatchingCancellable {
            ProcessBuilder(argv)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.fold(
            onSuccess = { true },
            onFailure = { e ->
                println("splice: failed to start the daemon: ${e.message}")
                false
            },
        )

    /** JW-01: shown when the daemon never answers after a cold start. Reads only the filesystem. */
    internal fun printBootLogTail() {
        val bootLog = logsDir().resolve("daemon-boot.log")
        // DR-68: a boot log that EXISTS but cannot be read is diagnosis gold going missing —
        // say so; only proven absence stays quiet.
        Cancellables.runCatchingCancellable {
            Files.readAllLines(bootLog).takeLast(BOOT_LOG_TAIL_LINES)
        }.onSuccess { tail ->
            println("splice: daemon did not come up — last boot output ($bootLog):")
            tail.forEach(::println)
        }.onFailure { failure ->
            val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                !Files.exists(bootLog, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            if (!genuinelyAbsent) {
                println(
                    "splice: daemon did not come up — boot log $bootLog is unreadable " +
                        "(${SafeFailureText.render(failure)})",
                )
            }
        }
    }
}

// ~2s bounded wait (8 x POLL_INTERVAL_MS) for a stopping daemon's control port to free before a
// cold start — long enough for a normal exit, short enough to abort-with-instructions if wedged.
private const val PORT_FREE_POLLS = 8
private const val BOOT_LOG_TAIL_LINES = 15
private const val POLL_INTERVAL_MS = 250L
