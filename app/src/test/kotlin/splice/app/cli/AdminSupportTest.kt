// NEW (G25): idle heap uncommit — DEFAULT_JVM_OPTS must carry -XX:G1PeriodicGCInterval=60000
// alongside the pre-existing G10 flags (-Xmx2048m, -XX:+UseStringDeduplication), since both
// cold-start paths (AdminSupport.spawnDaemon and app/src/main/dist/bin/splice-launch) are meant to agree.
package splice.app.cli

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.cli.daemon.DaemonLaunch
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

class AdminSupportTest {

    @Test
    fun `DEFAULT_JVM_OPTS carries the G1 periodic GC interval flag`() {
        assertTrue(AdminSupport.DEFAULT_JVM_OPTS.contains("-XX:G1PeriodicGCInterval=60000"))
    }

    @Test
    fun `DEFAULT_JVM_OPTS keeps the pre-existing heap cap and string-dedup flags`() {
        assertTrue(AdminSupport.DEFAULT_JVM_OPTS.contains("-Xmx2048m"))
        assertTrue(AdminSupport.DEFAULT_JVM_OPTS.contains("-XX:+UseStringDeduplication"))
        assertTrue(AdminSupport.DEFAULT_JVM_OPTS.contains("-XX:G1PeriodicGCInterval=60000"))
    }

    // The restart-refuses-while-bound wall: ensureDaemon must NOT cold-start into a still-bound control
    // port (that new daemon would win the just-released lock, then die on the uncaught control bind,
    // leaving zero serving). It waits the bounded window instead of spawning immediately.
    @Test
    fun `ensureDaemon refuses to cold-start while the control port is still bound`() {
        val server = ServerSocket(0)
        try {
            val start = System.nanoTime()
            val started = AdminSupport.ensureDaemon(server.localPort)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertFalse(started, "spawning INTO a still-bound control port must be refused")
            assertTrue(elapsedMs >= 1_000, "the gate waits the bounded window while bound, was ${elapsedMs}ms")
        } finally {
            server.close()
        }
    }

    // HD-18 review: `java.class.path` describes how the JVM was LAUNCHED, not which jar this class
    // came out of, and a single `.jar` entry is not proof of `java -jar splice.jar` — a pathing jar
    // (an IDE/JUnit long-classpath wrapper whose manifest carries the real Class-Path) is one entry
    // too, and it is not this build. selfJar() must ignore the property entirely and locate itself,
    // so a daemon cold start can never become `java -jar <someone-else's>.jar daemon` and doctor can
    // never report OK on a jar that has no splice Main-Class.
    @Test
    fun `selfJar ignores a single-entry launcher classpath`() {
        val launcher = Files.createTempDirectory("selfjar").resolve("launcher.jar")
        Files.writeString(launcher, "not a splice build")
        val saved = System.getProperty("java.class.path")
        try {
            System.setProperty("java.class.path", launcher.toString())
            assertFalse(
                AdminSupport.selfJar() == launcher,
                "a single-entry launcher classpath must never be mistaken for this build",
            )
        } finally {
            System.setProperty("java.class.path", saved)
        }
    }

    // selfJar() locates itself by a resource NAME, a string nothing links or renames with. This
    // pins the literal to the class it is meant to name (reflection is fine here — test sources are
    // exempt from kt-no-reflection-in-production, and checking the literal is the whole point).
    @Test
    fun `the self-locating resource name matches AdminSupport's own class file`() {
        val expected = AdminSupport::class.java.name.replace('.', '/') + ".class"
        assertTrue(expected == "splice/app/cli/AdminSupport.class", "was: $expected")
        assertTrue(
            AdminSupport::class.java.classLoader.getResource(expected) != null,
            "$expected must resolve on the loader that holds this build",
        )
    }

    // F149 (review #94): jar and logsDir ride as argv DATA, never interpolated into the sh -c
    // string — an apostrophe in the install path ("/home/o'brien") used to break out of the
    // single-quoted literal and kill the cold start on a shell parse error.
    @Test
    fun `daemon launch passes jar and logsDir as positional argv, never inside the shell string`() {
        val jar = Path.of("/home/o'brien/splice.jar")
        val logs = Path.of("/home/o'brien/.splice/logs")
        val argv = DaemonLaunch().daemonLaunchArgv(jar, logs)
        val script = argv[argv.indexOf("-c") + 1]
        assertFalse(script.contains("o'brien"), "paths must not be interpolated into the shell script")
        assertTrue(argv.takeLast(2) == listOf(jar.toString(), logs.toString()), "paths ride as positional argv")
        assertTrue(script.contains("\"\$1\"") && script.contains("\"\$2\""), "the script reads them as data")
    }

    // JW-01: the spawned JVM's output lands in daemon-boot.log, never /dev/null — a boot stack
    // trace has to be tailable. /dev/null survives only as the fallback for a logs dir the
    // redirect probe cannot write. The shim's twin of this redirect is rehearsed in
    // tools/release's launcher harness ("JW-01 the boot log is written and shown").
    @Test
    fun `daemon launch redirects the JVM into the boot log, dev-null only as the unwritable fallback - JW-01`() {
        val argv = DaemonLaunch().daemonLaunchArgv(Path.of("/opt/splice.jar"), Path.of("/var/log/splice"))
        val script = argv[argv.indexOf("-c") + 1]
        assertTrue(script.contains("B=\"\$L/daemon-boot.log\""), "the boot log is named off the logs dir: $script")
        assertTrue(
            script.contains("daemon >>\"\$B\" 2>&1"),
            "the JVM's stdout and stderr append to the boot log: $script",
        )
        val fallback = script.indexOf("daemon >/dev/null 2>&1")
        assertTrue(fallback > script.indexOf("else"), "/dev/null is reachable only through the else branch: $script")
    }
}
