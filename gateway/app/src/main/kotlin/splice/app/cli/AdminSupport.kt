// NEW: shared helpers for the operator-facing CLI (status/dashboard/setup) — daemon liveness,
// detached cold-start, browser open, self-jar discovery, mgmt-key read. Kept together so the
// commands read like a story. :app is wall-exempt for println (a terminal tool writes to stdout).
// Daemon up/spawn/wait bodies live in DaemonLaunch.kt (concentration HIGH, 2026-08-19).
package splice.app.cli

import splice.app.LoginIo
import splice.app.TopologyLoader
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.topology.TopologyKnobLayer
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal object AdminSupport {

    // The cold-start argv, boot-log tail, and daemon up/spawn/wait cluster live in DaemonLaunch.kt.
    private val launch = DaemonLaunch()

    /** The effective control port using the daemon's exact TOML < state < env precedence. */
    fun controlPort(envReader: EnvReader = EnvReader(System::getenv)): Int {
        val configPath = TopologyLoader.configPath(envReader)
        // DR-41b, same F3 lesson RestartCommand already carries: a corrupt TOML silently
        // degrading to default ports makes a RUNNING daemon look stopped. Say it (stderr —
        // stdout belongs to the verb's own output).
        val topology = Cancellables.runCatchingCancellable {
            TopologyLoader.loadOrMaterialize(configPath)
        }.onFailure {
            System.err.println(
                "splice: could not read $configPath (${it.message}) — " +
                    "using default ports; a running daemon may appear stopped",
            )
        }.getOrNull()
        return controlPort(topology, envReader)
    }

    /** Same, from an already-loaded (or absent) topology — doctor uses this so a diagnostic
     *  never MATERIALIZES the starter config as a side effect. [envReader] threads through the
     *  whole port resolution (StatePaths + ConfigService env layer) so a hermetic caller never
     *  reads the real process environment or state dir. */
    fun controlPort(topology: Topology?, envReader: EnvReader = EnvReader(System::getenv)): Int =
        ConfigService(
            StatePaths(envReader = envReader),
            // No topology (fresh machine / broken TOML) still resolves through the layered config:
            // the old null-branch returned the hardcoded default, silently IGNORING the state
            // config.json and SPLICE_CONTROL_PORT layers — which both broke hermetic test rigs
            // (an ambient real daemon answered instead) and diverged from the launch shim's own
            // resolution (JW-05 discovery, 2026-08-07).
            headOverrides = topology?.let { TopologyKnobLayer(it).configOverrides() } ?: emptyMap(),
            envReader = envReader,
        ).getConfig().controlPort

    /** True only when the listener answers splice's versioned HTTP health contract. */
    fun daemonUp(port: Int = controlPort()): Boolean = launch.daemonUp(port)

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
                ?.let { Paths.get(java.net.URI(it.path.substringBefore(JAR_URL_SEPARATOR))) }
        }.getOrNull()
        if (loc != null) return loc
        val installed = home().resolve(".local").resolve("share").resolve("splice").resolve("splice.jar")
        // DR-70: an unreadable installed jar is not a dev build — return it and let the consumer
        // fail with the real access error rather than misreporting "not installed".
        return Cancellables.runCatchingCancellable { Files.getLastModifiedTime(installed) }
            .exceptionOrNull()
            .let { failure ->
                val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                    !Files.exists(installed, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                if (failure != null && !genuinelyAbsent) {
                    println(
                        "splice: $installed unreadable (${SafeFailureText.render(failure)}) — " +
                            "treating it as the installed jar; commands may fail until access is fixed",
                    )
                }
                if (genuinelyAbsent) null else installed
            }
    }

    /** Cold-start the daemon detached (survives this CLI exiting) and wait until it answers. */
    fun ensureDaemon(port: Int = controlPort()): Boolean = launch.ensureDaemon(port)

    /** True while something still holds [port] — a TCP connect succeeds (or is ambiguous: timeout/IO).
     *  False ONLY on an explicit refusal (ConnectException), i.e. the listener is actually gone. Both
     *  the restart cold-start gate and the stop confirmation (DaemonStop.stopDaemon) read this,
     *  because "/health stopped answering" is NOT proof the old JVM freed its ports. */
    fun controlPortBound(port: Int): Boolean = launch.controlPortBound(port)

    fun openUrl(url: String): Boolean = LoginIo().openBrowser(url)

    fun mgmtKey(envReader: EnvReader = EnvReader(System::getenv)): String? =
        runCatching { Files.readString(StatePaths(envReader = envReader).mgmtKeyFile).trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() }

    fun home(): Path = Paths.get(System.getProperty("user.home"))

    // DR-70 (the DR-59 posture at CLI assembly): denied access to an auth file is not
    // logged-out — status/setup must not tell the operator to re-login through a chmod.
    fun authPresent(authFile: String): Boolean {
        val path = Paths.get(TopologyLoader.expandHome(authFile))
        return Cancellables.runCatchingCancellable { Files.getLastModifiedTime(path) }
            .exceptionOrNull()
            .let { failure ->
                val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                    !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                if (failure != null && !genuinelyAbsent) {
                    println(
                        "splice: $path unreadable (${SafeFailureText.render(failure)}) — " +
                            "treating the credential as present; fix access, not login",
                    )
                }
                !genuinelyAbsent
            }
    }

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

    // Bounded heap + string-dedup: safe for hundreds of concurrent streams, small for a laptop.
    // The shell `${SPLICE_JVM_OPTS:-...}` lets an operator override without touching code.
    // G1PeriodicGCInterval: idle heap uncommit — a daemon that goes quiet still returns freed
    // pages to the OS instead of holding them until the next GC is triggered by allocation.
    internal const val DEFAULT_JVM_OPTS = "-Xmx2048m -XX:+UseStringDeduplication -XX:G1PeriodicGCInterval=60000"
}

/** [AdminSupport]'s own class file, the resource selfJar() locates this build by. */
private const val SELF_CLASS_RESOURCE = "splice/app/cli/AdminSupport.class"

/** What a `jar:` URL puts between the archive and the entry inside it. */
private const val JAR_URL_SEPARATOR = "!/"
