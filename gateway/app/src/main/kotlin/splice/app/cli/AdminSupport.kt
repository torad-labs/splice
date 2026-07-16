// NEW: shared helpers for the operator-facing CLI (status/dashboard/setup) — daemon liveness,
// detached cold-start, browser open, self-jar discovery, mgmt-key read. Kept together so the
// commands read like a story. :app is wall-exempt for println (a terminal tool writes to stdout).
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.config.StatePaths
import java.io.IOException
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
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException", "SwallowedException", "ReturnCount")
    fun ensureDaemon(port: Int = controlPort()): Boolean {
        if (daemonUp(port)) return true
        val jar = selfJar() ?: run {
            println("splice: can't find the splice jar to start the daemon (run: splice install).")
            return false
        }
        println("splice: starting the daemon…")
        try {
            ProcessBuilder("sh", "-c", "nohup java -jar '$jar' daemon >/dev/null 2>&1 &")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            println("splice: failed to start the daemon: ${e.message}")
            return false
        }
        repeat(STARTUP_POLLS) {
            if (daemonUp(port)) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return daemonUp(port)
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException", "SwallowedException")
    fun openUrl(url: String): Boolean = try {
        val os = System.getProperty("os.name").lowercase()
        val cmd = when {
            os.contains("mac") -> listOf("open", url)
            os.contains("nux") || os.contains("nix") -> listOf("xdg-open", url)
            else -> return false
        }
        ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        true
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        false
    }

    fun mgmtKey(): String? =
        runCatching { Files.readString(StatePaths().mgmtKeyFile).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun home(): Path = Paths.get(System.getProperty("user.home"))

    fun authPresent(authFile: String): Boolean =
        runCatching { Files.exists(Paths.get(TopologyLoader.expandHome(authFile))) }.getOrDefault(false)

    /** Read a y/n from the terminal; returns [default] when there's no TTY (piped/CI). */
    @Suppress("SwallowedException")
    fun confirm(prompt: String, default: Boolean = true): Boolean {
        if (System.console() == null) return default
        print("$prompt ${if (default) "[Y/n]" else "[y/N]"} ")
        val line = try {
            readlnOrNull()?.trim()?.lowercase()
        } catch (e: IOException) {
            null
        }
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
}
