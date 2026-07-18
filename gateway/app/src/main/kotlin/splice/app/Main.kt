// NEW: process entry (P4-SUP). The ONLY place runBlocking is legal (the walls exempt Main.kt +
// cli/). Acquires the single-flight daemon lock, loads topology, starts the daemon, installs a
// shutdown hook. `splice daemon` is the default; other subcommands route to the CLI (P5-CLI).
package splice.app

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import splice.core.config.StatePaths
import splice.core.util.runCatchingCancellable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.LocalTime
import java.time.temporal.ChronoUnit

public fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        null, "daemon", "start" -> runDaemon()
        else -> splice.app.cli.runCli(args)
    }
}

private fun runDaemon() {
    val statePaths = StatePaths()
    val lock = DaemonLock(statePaths.daemonLockFile)
    if (!lock.tryAcquire()) {
        System.err.println("[daemon] another splice daemon holds the lock — exiting (the winner serves)")
        return
    }
    val topologyPath = TopologyLoader.configPath()
    val topology = TopologyLoader.loadOrMaterialize(topologyPath)
    val distPath = Paths.get(System.getProperty("user.dir"), "..", "webui", "dist", "index.html")
    val daemon = Daemon(
        topology,
        statePaths,
        Daemon.dashboardFrom(distPath),
        log = persistentLogger(statePaths.logsDir),
    )

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking { runCatching { daemon.stop() } }
            lock.close()
        },
    )

    runBlocking {
        daemon.start()
        // park the main coroutine; the embedded servers run on their own threads
        awaitCancellation()
    }
}

// Timestamps every log line and tees it to a persistent, append-only daemon.log (so failures and
// slow turns survive restarts and are `tail -f`-able) in addition to stderr. Best-effort file I/O.
private fun persistentLogger(logsDir: Path): (String) -> Unit {
    runCatchingCancellable { Files.createDirectories(logsDir) }
    val file = logsDir.resolve("daemon.log")
    val gate = Any()
    return { msg ->
        val line = "[${LocalTime.now().truncatedTo(ChronoUnit.SECONDS)}] $msg"
        System.err.print(line)
        synchronized(gate) { runCatchingCancellable { Files.writeString(file, line, CREATE, APPEND) } }
    }
}
