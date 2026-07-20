// NEW: process entry (P4-SUP). The ONLY place runBlocking is legal (the walls exempt Main.kt +
// cli/). Acquires the single-flight daemon lock, loads topology, starts the daemon, installs a
// shutdown hook. `splice daemon` is the default; other subcommands route to the CLI (P5-CLI).
package splice.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import splice.core.config.StatePaths
import splice.core.util.AsyncFileIo
import splice.core.util.runCatchingCancellable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.security.Security
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess

public fun main(args: Array<String>) {
    // Kill JVM negative-DNS caching BEFORE any lookup (kimi 07:00 burst, 2026-07-18): the JVM
    // caches a FAILED lookup for 10s by default, so one resolver timeout for api.kimi.com poisoned
    // every following request — 37 turn failures from one blip, including 5ms "failures" that never
    // touched the network. A long-lived proxy must re-ask on each miss; successful-lookup caching
    // (30s) stays as is. Retry backoff (200-800ms) only works against real lookups, not a poison
    // window three times its whole budget. Pin it explicitly too — the positive TTL's vendor
    // default is unspecified/-1 without a SecurityManager, so leaving it implicit is the same
    // latent-default trap G10 (stale shim) already burned once.
    Security.setProperty("networkaddress.cache.negative.ttl", "0")
    Security.setProperty("networkaddress.cache.ttl", "30")
    when (args.firstOrNull()) {
        null, "daemon", "start" -> runDaemon()
        else -> exitProcess(splice.app.cli.runCli(args))
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
    val log = persistentLogger(statePaths.logsDir)
    val shutdownSignal = CompletableDeferred<Unit>()
    splice.app.cli.shimStalenessWarning()?.let { log("$it\n") }
    val daemon = Daemon(
        topology,
        statePaths,
        Daemon.dashboardFrom(distPath),
        log = log,
        shutdownDaemon = { shutdownSignal.complete(Unit) },
    )

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking { runCatchingDaemonBoundary { daemon.stop() } }
            AsyncFileIo.drain()
            lock.close()
        },
    )

    runBlocking {
        try {
            daemon.start()
            shutdownSignal.await()
        } finally {
            daemon.stop()
            AsyncFileIo.drain()
            lock.close()
        }
    }
}

// Timestamps every log line and tees it to a persistent daemon.log (so failures and slow turns
// survive restarts and are `tail -f`-able) in addition to stderr. The turn path only enqueues an
// immutable line; the bounded process-wide file lane owns stderr/filesystem latency. The writer
// stays OPEN for the daemon's lifetime. A failed write drops the writer so the
// next line reopens. SIZE-ROTATION (audit 2026-07-18: daemon.log grew forever, ~1KB/turn): at
// MAX_LOG_BYTES the file is moved to daemon.log.1 (one generation kept) and a fresh file opened.
private fun persistentLogger(logsDir: Path): (String) -> Unit {
    runCatchingCancellable { Files.createDirectories(logsDir) }
    val file = logsDir.resolve("daemon.log")
    val rolled = logsDir.resolve("daemon.log.1")
    var writer: java.io.Writer? = null
    var written = runCatchingCancellable { if (Files.exists(file)) Files.size(file) else 0L }.getOrDefault(0L)
    return { msg ->
        val line = "[${LocalTime.now().truncatedTo(ChronoUnit.SECONDS)}] $msg"
        AsyncFileIo.submit {
            System.err.print(line)
            runCatchingCancellable {
                if (written >= MAX_LOG_BYTES) {
                    writer?.close()
                    Files.move(file, rolled, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    writer = null
                    written = 0L
                }
                val w = writer ?: Files.newBufferedWriter(file, CREATE, APPEND).also { writer = it }
                w.write(line)
                w.flush()
                written += line.toByteArray(Charsets.UTF_8).size
            }.onFailure {
                runCatchingCancellable { writer?.close() }
                writer = null
            }
        }
    }
}

// One rolled generation at 64MB caps daemon.log disk at ~128MB — plenty of tail history, bounded.
private const val MAX_LOG_BYTES = 64L * 1024 * 1024
