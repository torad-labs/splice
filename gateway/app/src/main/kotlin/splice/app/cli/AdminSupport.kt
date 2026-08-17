// NEW: shared helpers for the operator-facing CLI (status/dashboard/setup) — daemon liveness,
// detached cold-start, browser open, self-jar discovery, mgmt-key read. Kept together so the
// commands read like a story. :app is wall-exempt for println (a terminal tool writes to stdout).
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.app.TopologyLoader
import splice.core.GATEWAY_VERSION
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.util.Cancellables
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal object AdminSupport {
    private val json = Json { ignoreUnknownKeys = true }

    // The cold-start argv and the boot-log tail live on DaemonLaunch, not here: this object sits at
    // detekt's 15-function budget, which is the same reason both were file-level functions before
    // the no-top-level-functions law (2026-08-15) gave them a class.
    private val launch = DaemonLaunch()

    /** The effective control port using the daemon's exact TOML < state < env precedence. */
    fun controlPort(): Int =
        controlPort(runCatching { TopologyLoader.loadOrMaterialize(TopologyLoader.configPath()) }.getOrNull())

    /** Same, from an already-loaded (or absent) topology — doctor uses this so a diagnostic
     *  never MATERIALIZES the starter config as a side effect. [envReader] threads through the
     *  whole port resolution (StatePaths + ConfigService env layer) so a hermetic caller never
     *  reads the real process environment or state dir. */
    fun controlPort(topology: Topology?, envReader: (String) -> String? = System::getenv): Int =
        ConfigService(
            StatePaths(envReader = envReader),
            // No topology (fresh machine / broken TOML) still resolves through the layered config:
            // the old null-branch returned the hardcoded default, silently IGNORING the state
            // config.json and SPLICE_CONTROL_PORT layers — which both broke hermetic test rigs
            // (an ambient real daemon answered instead) and diverged from the launch shim's own
            // resolution (JW-05 discovery, 2026-08-07).
            headOverrides = topology?.configOverrides() ?: emptyMap(),
            envReader = envReader,
        ).getConfig().controlPort

    /** True only when the listener answers splice's versioned HTTP health contract. */
    fun daemonUp(port: Int = controlPort()): Boolean = Cancellables.runCatchingCancellable {
        val connection = URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatchingCancellable false
            val health = connection.inputStream.bufferedReader().use { it.readText() }
            val obj = json.parseToJsonElement(health).jsonObject
            // "Up" is the control server answering with the matching version — NOT health `ok`.
            // Since 2026-08-12 `ok` means "a turn can complete", so a stalled head flips it false
            // while the daemon is very much alive; reading `ok` here made `splice status` report a
            // running daemon as "stopped" and ensureDaemon loop on a bound port (F4/F10).
            obj["version"]?.jsonPrimitive?.content == GATEWAY_VERSION
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    /** The running jar, so a spawned daemon reuses the exact same build.
     *
     *  Located the way [splice.app.DashboardHtml] locates the web UI — by asking the class loader
     *  for a resource by NAME — instead of by reflecting on this class's protection domain. The
     *  resource asked for is this object's own class file, so a `jar:` URL answers exactly the
     *  question the protection domain used to answer ("which archive is the code running right now
     *  in?"), and a `file:` URL is an exploded classes dir (a dev build) that falls through to the
     *  installed copy, exactly as the old `.endsWith(".jar")` guard made it.
     *
     *  NOT `java.class.path` (HD-18 review): that property describes how the JVM was LAUNCHED, and
     *  a lone `.jar` entry is not proof of `java -jar splice.jar`. A pathing jar — an IDE or JUnit
     *  long-classpath wrapper whose manifest carries the real Class-Path — is a single entry too,
     *  and naming it would cold-start the daemon as `java -jar <wrapper>.jar daemon`, which has no
     *  splice Main-Class. The resource lookup cannot make that mistake: it follows the loader that
     *  actually holds these bytes. [SELF_CLASS_RESOURCE] is the one string this costs, and
     *  AdminSupportTest pins it to the class it names. */
    fun selfJar(): Path? {
        val loc = runCatching {
            ClassLoader.getSystemResource(SELF_CLASS_RESOURCE)
                ?.takeIf { it.protocol == "jar" }
                ?.let { Paths.get(URI(it.path.substringBefore(JAR_URL_SEPARATOR))) }
        }.getOrNull()
        if (loc != null) return loc
        val installed = home().resolve(".local").resolve("share").resolve("splice").resolve("splice.jar")
        return installed.takeIf { Files.exists(it) }
    }

    /** Cold-start the daemon detached (survives this CLI exiting) and wait until it answers. */
    fun ensureDaemon(port: Int = controlPort()): Boolean {
        if (daemonUp(port)) return true
        val jar = startableJar(port) ?: return false
        println("splice: starting the daemon…")
        val up = spawnDaemon(jar) && waitUntilUp(port)
        // JW-01: when the daemon never answers, the reason is in the boot log — print it here
        // instead of leaving "starting the daemon…" as the last line the operator ever sees.
        if (!up) launch.printBootLogTail()
        return up
    }

    /** True while something still holds [port] — a TCP connect succeeds (or is ambiguous: timeout/IO).
     *  False ONLY on an explicit refusal (ConnectException), i.e. the listener is actually gone. Both
     *  the restart cold-start gate ([startableJar]) and the stop confirmation (ControlPlaneClient
     *  .stopDaemon) read this, because "/health stopped answering" is NOT proof the old JVM freed its
     *  ports — the process can linger on non-daemon Netty threads with ports still bound (BS-4). */
    fun controlPortBound(port: Int): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS) }
            true
        } catch (_: ConnectException) {
            false
        } catch (_: IOException) {
            // A connect timeout or other transient I/O error is ambiguous — treat as still-bound so an
            // uncertain signal never green-lights a racing cold start.
            true
        }

    /** The jar to (re)launch from, or null (with a printed reason) when cold-start must be REFUSED.
     *  Waits a short bounded window for the control port to free first: spawning while a prior daemon
     *  still holds it (stopped answering /health but not yet exited — BS-4 DEFECT B) would let the new
     *  daemon win the just-released lock and then die on the uncaught control bind, leaving zero serving. */
    private fun startableJar(port: Int): Path? {
        var polls = PORT_FREE_POLLS
        while (controlPortBound(port) && polls-- > 0) Thread.sleep(POLL_INTERVAL_MS)
        if (controlPortBound(port)) {
            println("splice: control port $port is still bound (a daemon is still shutting down) — retry in a moment")
            return null
        }
        val jar = selfJar()
        if (jar == null) println("splice: can't find the splice jar to start the daemon (run: splice install).")
        return jar
    }

    /** Spawn the detached daemon process; false (with a message) if it can't be launched.
     *  JVM opts (bounded heap by default) ride $SPLICE_JVM_OPTS, expanded BY THE SHELL so the
     *  wall keeping System.getenv out of non-config code stays intact — splice-launch exports the
     *  same default, so both cold-start paths agree (audit 2026-07-18: no -Xmx → 1000-stream OOM). */
    private fun spawnDaemon(jar: Path): Boolean =
        Cancellables.runCatchingCancellable {
            ProcessBuilder(launch.daemonLaunchArgv(jar, StatePaths().logsDir))
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

    fun openUrl(url: String): Boolean = Cancellables.runCatchingCancellable {
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

    fun mgmtKey(envReader: (String) -> String? = System::getenv): String? =
        runCatching { Files.readString(StatePaths(envReader = envReader).mgmtKeyFile).trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() }

    fun home(): Path = Paths.get(System.getProperty("user.home"))

    fun authPresent(authFile: String): Boolean =
        runCatching { Files.exists(Paths.get(TopologyLoader.expandHome(authFile))) }.getOrDefault(false)

    /** Read a y/n from the terminal; returns [default] when there's no TTY (piped/CI). */
    fun confirm(prompt: String, default: Boolean = true): Boolean {
        if (System.console() == null) return default
        print("$prompt ${if (default) "[Y/n]" else "[y/N]"} ")
        val line = Cancellables.runCatchingCancellable { readlnOrNull()?.trim()?.lowercase() }.getOrNull()
        return when (line) {
            null, "" -> default
            "y", "yes" -> true
            else -> false
        }
    }

    private const val PROBE_TIMEOUT_MS = 400
    private const val STARTUP_POLLS = 60
    private const val POLL_INTERVAL_MS = 250L

    // ~2s bounded wait (8 x POLL_INTERVAL_MS) for a stopping daemon's control port to free before a
    // cold start — long enough for a normal exit, short enough to abort-with-instructions if wedged.
    private const val PORT_FREE_POLLS = 8

    // Bounded heap + string-dedup: safe for hundreds of concurrent streams, small for a laptop.
    // The shell `${SPLICE_JVM_OPTS:-...}` lets an operator override without touching code.
    // G1PeriodicGCInterval: idle heap uncommit — a daemon that goes quiet still returns freed
    // pages to the OS instead of holding them until the next GC is triggered by allocation.
    internal const val DEFAULT_JVM_OPTS = "-Xmx2048m -XX:+UseStringDeduplication -XX:G1PeriodicGCInterval=60000"
}

/** The two cold-start-adjacent helpers AdminSupport cannot hold: that object is at detekt's
 *  15-function budget, so folding these in fails TooManyFunctions. They were top-level functions
 *  for exactly that reason; the no-top-level-functions law (2026-08-15) turns the pair into a
 *  named collaborator instead. Both members keep their old function names. */
internal class DaemonLaunch {

    /** The cold-start command as argv. The jar and logs dir ride as positional $1/$2 DATA, never
     *  interpolated into the script text: an apostrophe in the install path ("/home/o'brien")
     *  broke out of the old single-quoted literal and the cold start died on a shell parse error
     *  (review #94, F149). SPLICE_JVM_OPTS stays a shell expansion by design (see spawnDaemon). */
    internal fun daemonLaunchArgv(jar: Path, logsDir: Path): List<String> {
        val opts = AdminSupport.DEFAULT_JVM_OPTS
        return listOf(
            "sh",
            "-c",
            // JW-01: the spawned JVM's output lands in daemon-boot.log (rolled at 1MB, one
            // generation), never /dev/null; an unwritable logs dir degrades the redirect
            // instead of breaking the launch. Mirrors bin/splice-launch byte-for-byte in
            // behaviour — the two cold-start paths must not drift.
            "L=\"\$2\"; " +
                "B=\"\$L/daemon-boot.log\"; mkdir -p \"\$L\" 2>/dev/null; " +
                "[ -f \"\$B\" ] && [ \"\$(wc -c <\"\$B\" 2>/dev/null || echo 0)\" -gt 1048576 ] " +
                "&& mv -f \"\$B\" \"\$B.1\" 2>/dev/null; " +
                "if ( : >>\"\$B\" ) 2>/dev/null; then " +
                "nohup java \${SPLICE_JVM_OPTS:-$opts} -jar \"\$1\" daemon >>\"\$B\" 2>&1 & " +
                "else nohup java \${SPLICE_JVM_OPTS:-$opts} -jar \"\$1\" daemon >/dev/null 2>&1 & fi",
            "sh",
            jar.toString(),
            logsDir.toString(),
        )
    }

    /** JW-01: shown when the daemon never answers after a cold start. Reads only the filesystem. */
    internal fun printBootLogTail() {
        val bootLog = StatePaths().logsDir.resolve("daemon-boot.log")
        if (!Files.exists(bootLog)) return
        println("splice: daemon did not come up — last boot output ($bootLog):")
        Cancellables.runCatchingCancellable {
            Files.readAllLines(bootLog).takeLast(BOOT_LOG_TAIL_LINES).forEach(::println)
        }
    }
}

private const val BOOT_LOG_TAIL_LINES = 15

/** [AdminSupport]'s own class file, the resource selfJar() locates this build by. */
private const val SELF_CLASS_RESOURCE = "splice/app/cli/AdminSupport.class"

/** What a `jar:` URL puts between the archive and the entry inside it. */
private const val JAR_URL_SEPARATOR = "!/"
