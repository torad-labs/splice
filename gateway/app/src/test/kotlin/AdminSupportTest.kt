// NEW (G25): idle heap uncommit — DEFAULT_JVM_OPTS must carry -XX:G1PeriodicGCInterval=60000
// alongside the pre-existing G10 flags (-Xmx2048m, -XX:+UseStringDeduplication), since both
// cold-start paths (AdminSupport.spawnDaemon and bin/splice-launch) are meant to agree.
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.cli.AdminSupport
import splice.app.cli.daemonLaunchArgv
import splice.core.GATEWAY_VERSION
import java.net.InetSocketAddress
import java.net.ServerSocket
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

    @Test
    fun `daemon probe requires the versioned splice HTTP health contract`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var body = """{"ok":true,"version":"unrelated-service"}"""
        server.createContext("/health") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            assertFalse(AdminSupport.daemonUp(server.address.port))
            body = """{"ok":true,"version":"$GATEWAY_VERSION"}"""
            assertTrue(AdminSupport.daemonUp(server.address.port))
        } finally {
            server.stop(0)
        }
    }

    // BS-4 DEFECT B: "/health stopped answering" is not proof the old daemon freed its control port,
    // so the cold-start gate reads the port itself. A bound-but-not-serving listener must read bound.
    @Test
    fun `controlPortBound reports a bound port as bound and a freed port as free`() {
        val server = ServerSocket(0)
        try {
            assertTrue(AdminSupport.controlPortBound(server.localPort), "an accepting listener is bound")
        } finally {
            server.close()
        }
        assertFalse(AdminSupport.controlPortBound(server.localPort), "a closed port refuses — free")
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

    // F149 (review #94): jar and logsDir ride as argv DATA, never interpolated into the sh -c
    // string — an apostrophe in the install path ("/home/o'brien") used to break out of the
    // single-quoted literal and kill the cold start on a shell parse error.
    @Test
    fun `daemon launch passes jar and logsDir as positional argv, never inside the shell string`() {
        val jar = Path.of("/home/o'brien/splice.jar")
        val logs = Path.of("/home/o'brien/.claude-codex/logs")
        val argv = daemonLaunchArgv(jar, logs)
        val script = argv[argv.indexOf("-c") + 1]
        assertFalse(script.contains("o'brien"), "paths must not be interpolated into the shell script")
        assertTrue(argv.takeLast(2) == listOf(jar.toString(), logs.toString()), "paths ride as positional argv")
        assertTrue(script.contains("\"\$1\"") && script.contains("\"\$2\""), "the script reads them as data")
    }
}
