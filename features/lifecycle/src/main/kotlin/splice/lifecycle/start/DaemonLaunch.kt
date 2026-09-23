// NEW: cold-start argv + the ensureDaemon composer. Health probes live in
// DaemonHealth.kt; spawn/jar/boot-tail live in DaemonSpawn.kt; the unit-first ROUTE (V4-190)
// lives in SupervisedStart.kt. In features/lifecycle since LAYOUT-01: [DaemonColdStart] is the one
// public entry, shared by `splice dashboard` and `splice restart` (the only verbs that cold-start;
// status and doctor only probe). daemon-boot.log is named HERE so JW-01 stays anchored on this
// file, and DEFAULT_JVM_OPTS moved here from AdminSupport with the argv that is its one reader —
// the launch shim carries the same flag set and the two must agree.
package splice.lifecycle.start

import splice.core.GATEWAY_VERSION
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.daemonclient.DaemonHealth
import splice.daemonclient.DaemonSettings
import java.nio.file.Path
import java.time.Duration

/** The daemon cold start `splice dashboard` and `splice restart` share: the supervisor unit when this
 *  box has one, the raw spawn otherwise, then the wait for the expected version. [errors] carries the
 *  corrupt-TOML diagnostic the supervisor-unit read can raise; stdout belongs to the verb. */
public class DaemonColdStart(output: TerminalOutput, errors: TerminalOutput, env: EnvReader, jar: RunningJar) {

    private val launch = DaemonHealth().let { health ->
        DaemonLaunch(
            output,
            health,
            DaemonSpawn(output, health, jar),
            SupervisedStart(JdkSystemctl(), env, DaemonSettings(errors)),
        )
    }

    /** Cold-start the daemon detached (survives this CLI exiting) and wait until it answers with
     *  [expectedVersion] (this CLI's own, or the one an upgrade just activated). */
    public fun ensureDaemon(port: Int, expectedVersion: String = GATEWAY_VERSION): Boolean =
        launch.ensureDaemon(port, expectedVersion)
}

internal class DaemonLaunch(
    private val output: TerminalOutput,
    private val health: DaemonHealth,
    private val spawn: DaemonSpawn,
    private val supervised: SupervisedStart,
    private val startupPolls: Int = STARTUP_POLLS,
) {

    /** The cold-start command as argv. The jar and logs dir ride as positional $1/$2 DATA, never
     *  interpolated into the script text: an apostrophe in the install path ("/home/o'brien")
     *  broke out of the old single-quoted literal and the cold start died on a shell parse error
     *  (review #94, F149). SPLICE_JVM_OPTS stays a shell expansion by design (see spawnDaemon). */
    internal fun daemonLaunchArgv(jar: Path, logsDir: Path): List<String> {
        val opts = DEFAULT_JVM_OPTS
        return listOf(
            "sh",
            "-c",
            // JW-01: the spawned JVM's output lands in daemon-boot.log (rolled at 1MB, one
            // generation), never /dev/null; an unwritable logs dir degrades the redirect
            // instead of breaking the launch. Mirrors app/src/main/dist/bin/splice-launch byte-for-byte in
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

    /** Cold-start the daemon detached (survives this CLI exiting) and wait until it answers with
     *  [expectedVersion] (this CLI's own, or the one an upgrade just activated). */
    internal fun ensureDaemon(port: Int, expectedVersion: String = GATEWAY_VERSION): Boolean {
        if (health.daemonUp(port, expectedVersion)) return true
        return when (val route = supervised.route()) {
            is ColdStartRoute.Unit -> startUnit(route.unit, port, expectedVersion)
            is ColdStartRoute.Raw -> spawnHere(route.reason, port, expectedVersion)
        }
    }

    /** V4-190: the supervisor unit exists and this shell is on its defaults, so the unit is the only
     *  thing that may start a daemon here. A unit that never answers is reported with its journal;
     *  a second daemon is never spawned beside it (that is how every squatter of 2026-09-21 was born). */
    private fun startUnit(unit: String, port: Int, expectedVersion: String): Boolean {
        output.line("splice: starting $unit…")
        val up = supervised.start(unit) && waitUntilUp(port, expectedVersion)
        if (!up) {
            val budget = Duration.ofMillis(startupPolls * POLL_INTERVAL_MS).toSeconds()
            output.line(
                "splice: $unit did not answer /health with $expectedVersion within ${budget}s — never starting " +
                    "a second daemon beside it. See: systemctl --user status $unit; journalctl --user -u $unit -n 50",
            )
        }
        return up
    }

    /** The raw spawn: a box with no unit, or a shell a selector points at a daemon of its own. */
    private fun spawnHere(reason: String, port: Int, expectedVersion: String): Boolean {
        val jar = spawn.startableJar(port) ?: return false
        output.line("splice: starting the daemon here ($reason)…")
        val up = spawn.spawnDaemon(daemonLaunchArgv(jar, spawn.logsDir())) && waitUntilUp(port, expectedVersion)
        // JW-01: when the daemon never answers, the reason is in the boot log — print it here
        // instead of leaving "starting the daemon…" as the last line the operator ever sees.
        if (!up) spawn.printBootLogTail()
        return up
    }

    /** Poll until the daemon answers on [port] with [expectedVersion], or the startup budget runs out. */
    private fun waitUntilUp(port: Int, expectedVersion: String): Boolean {
        repeat(startupPolls) {
            if (health.daemonUp(port, expectedVersion)) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return health.daemonUp(port, expectedVersion)
    }
}

// V4-74: 62s = the old daemon's halt floor (57s) plus a 5s margin, because a restart now waits for
// the outgoing daemon to DRAIN ITS IN-FLIGHT TURNS (up to 45s) before it releases the lock. This
// budget must stay above DaemonLockWait's LOCK_WAIT_POLLS for the same reason that derived it from
// the floor: a spawner that gave up first would report a failure for a restart that was working.
internal const val STARTUP_POLLS = 248
private const val POLL_INTERVAL_MS = 250L

// Bounded heap + string-dedup: safe for hundreds of concurrent streams, small for a laptop.
// The shell `${SPLICE_JVM_OPTS:-...}` lets an operator override without touching code.
// G1PeriodicGCInterval: idle heap uncommit — a daemon that goes quiet still returns freed
// pages to the OS instead of holding them until the next GC is triggered by allocation.
internal const val DEFAULT_JVM_OPTS = "-Xmx2048m -XX:+UseStringDeduplication -XX:G1PeriodicGCInterval=60000"
