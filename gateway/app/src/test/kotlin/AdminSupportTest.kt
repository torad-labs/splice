// NEW (G25): idle heap uncommit — DEFAULT_JVM_OPTS must carry -XX:G1PeriodicGCInterval=60000
// alongside the pre-existing G10 flags (-Xmx2048m, -XX:+UseStringDeduplication), since both
// cold-start paths (AdminSupport.spawnDaemon and bin/splice-launch) are meant to agree.
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.cli.AdminSupport
import splice.core.GATEWAY_VERSION
import java.net.InetSocketAddress

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
}
