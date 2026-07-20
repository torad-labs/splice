// NEW: shared helpers for the operator-facing CLI (status/dashboard/setup) — daemon liveness,
// detached cold-start, browser open, self-jar discovery, mgmt-key read. Kept together so the
// commands read like a story. :app is wall-exempt for println (a terminal tool writes to stdout).
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.config.StatePaths
import splice.core.util.runCatchingCancellable
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal object AdminSupport {

    /** The control port from the topology (default 3096). */
    fun controlPort(): Int {
        val topo = runCatching { TopologyLoader.loadOrMaterialize(TopologyLoader.configPath()) }.getOrNull()
        return topo?.daemon?.controlPort?.takeIf { it > 0 } ?: DEFAULT_CONTROL_PORT
    }

    /** True if something is listening on the control port (the daemon is up). */
    fun daemonUp(port: Int = controlPort()): Boolean =
        runCatching {
            Socket().use {
                it.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS)
                true
            }
        }.getOrDefault(false)

    /** The running jar, so a spawned daemon reuses the exact same build. */
    fun selfJar(): Path? {
        val loc = runCatching {
            Paths.get(AdminSupport::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        if (loc != null && loc.toString().endsWith(".jar")) return loc
        val installed = home().resolve(".local").resolve("share").resolve("splice").resolve("splice.jar")
        return installed.takeIf { Files.exists(it) }
    }

    /** Cold-start the daemon detached (survives this CLI exiting) and wait until it answers. */
    fun ensureDaemon(port: Int = controlPort()): Boolean {
        if (daemonUp(port)) return true
        val jar = selfJar()
        if (jar == null) {
            println("splice: can't find the splice jar to start the daemon (run: splice install).")
            return false
        }
        println("splice: starting the daemon…")
        return spawnDaemon(jar) && waitUntilUp(port)
    }

    /** Spawn the detached daemon process; false (with a message) if it can't be launched.
     *  JVM opts (bounded heap by default) ride $SPLICE_JVM_OPTS, expanded BY THE SHELL so the
     *  wall keeping System.getenv out of non-config code stays intact — splice-launch exports the
     *  same default, so both cold-start paths agree (audit 2026-07-18: no -Xmx → 1000-stream OOM). */
    private fun spawnDaemon(jar: Path): Boolean =
        runCatchingCancellable {
            ProcessBuilder(
                "sh",
                "-c",
                "nohup java \${SPLICE_JVM_OPTS:-$DEFAULT_JVM_OPTS} -jar '$jar' daemon >/dev/null 2>&1 &",
            )
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

    /** Poll until the daemon answers on [port], or the startup budget runs out. */
    private fun waitUntilUp(port: Int): Boolean {
        repeat(STARTUP_POLLS) {
            if (daemonUp(port)) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return daemonUp(port)
    }

    fun openUrl(url: String): Boolean = runCatchingCancellable {
        val os = System.getProperty("os.name").lowercase()
        val cmd = when {
            os.contains("mac") -> listOf("open", url)
            os.contains("nux") || os.contains("nix") -> listOf("xdg-open", url)
            else -> return false
        }
        ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        true
    }.getOrDefault(false)

    fun mgmtKey(): String? =
        runCatching { Files.readString(StatePaths().mgmtKeyFile).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun home(): Path = Paths.get(System.getProperty("user.home"))

    fun authPresent(authFile: String): Boolean =
        runCatching { Files.exists(Paths.get(TopologyLoader.expandHome(authFile))) }.getOrDefault(false)

    /** Read a y/n from the terminal; returns [default] when there's no TTY (piped/CI). */
    fun confirm(prompt: String, default: Boolean = true): Boolean {
        if (System.console() == null) return default
        print("$prompt ${if (default) "[Y/n]" else "[y/N]"} ")
        val line = runCatchingCancellable { readlnOrNull()?.trim()?.lowercase() }.getOrNull()
        return when (line) {
            null, "" -> default
            "y", "yes" -> true
            else -> false
        }
    }

    private const val DEFAULT_CONTROL_PORT = 3096
    private const val PROBE_TIMEOUT_MS = 400
    private const val STARTUP_POLLS = 60
    private const val POLL_INTERVAL_MS = 250L

    // Bounded heap + string-dedup: safe for hundreds of concurrent streams, small for a laptop.
    // The shell `${SPLICE_JVM_OPTS:-...}` lets an operator override without touching code.
    // G1PeriodicGCInterval: idle heap uncommit — a daemon that goes quiet still returns freed
    // pages to the OS instead of holding them until the next GC is triggered by allocation.
    internal const val DEFAULT_JVM_OPTS = "-Xmx2048m -XX:+UseStringDeduplication -XX:G1PeriodicGCInterval=60000"
}
