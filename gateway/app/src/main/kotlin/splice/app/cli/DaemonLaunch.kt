// NEW: cold-start argv + the ensureDaemon composer. Health probes live in
// DaemonHealth.kt; spawn/jar/boot-tail live in DaemonSpawn.kt. AdminSupport
// keeps one-line public delegates so Status/Restart/Dashboard/Doctor call
// sites do not change. daemon-boot.log is named HERE so JW-01 stays
// path-anchored on this file. DEFAULT_JVM_OPTS still lives on AdminSupport
// because spawnDaemon and the launch shim must agree on the flag set.
package splice.app.cli

import java.nio.file.Path

internal class DaemonLaunch {

    private val health = DaemonHealth()
    private val spawn = DaemonSpawn(health)

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
    internal fun printBootLogTail() = spawn.printBootLogTail()

    /** True only when the listener answers splice's versioned HTTP health contract. */
    internal fun daemonUp(port: Int): Boolean = health.daemonUp(port)

    /** True while something still holds [port] — a TCP connect succeeds (or is ambiguous: timeout/IO).
     *  False ONLY on an explicit refusal (ConnectException), i.e. the listener is actually gone. */
    internal fun controlPortBound(port: Int): Boolean = health.controlPortBound(port)

    /** Cold-start the daemon detached (survives this CLI exiting) and wait until it answers. */
    internal fun ensureDaemon(port: Int): Boolean {
        if (health.daemonUp(port)) return true
        val jar = spawn.startableJar(port) ?: return false
        println("splice: starting the daemon…")
        val up = spawn.spawnDaemon(daemonLaunchArgv(jar, spawn.logsDir())) && waitUntilUp(port)
        // JW-01: when the daemon never answers, the reason is in the boot log — print it here
        // instead of leaving "starting the daemon…" as the last line the operator ever sees.
        if (!up) spawn.printBootLogTail()
        return up
    }

    /** Poll until the daemon answers on [port], or the startup budget runs out. */
    private fun waitUntilUp(port: Int): Boolean {
        repeat(STARTUP_POLLS) {
            if (health.daemonUp(port)) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return health.daemonUp(port)
    }
}

private const val STARTUP_POLLS = 60
private const val POLL_INTERVAL_MS = 250L
