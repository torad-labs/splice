package splice.control.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** /api/mcp's per-instance memory: the hosted child's resident bytes while it runs, absent otherwise. */
class McpStatusMemoryTest : McpHostFixture() {

    @Test
    fun `a running hosted server reports its resident bytes and a stopped one reports none`() = runBlocking {
        assumeTrue(Files.exists(Path.of("/proc/self/status")), "VmRSS is read from /proc (Linux)")
        boot()
        assertNull(status("fake")["rss_bytes"], "not started yet")
        init()
        val rss = checkNotNull(status("fake")["rss_bytes"]?.jsonPrimitive?.content?.toLong())
        assertTrue(rss > 0, "rss_bytes=$rss")
        host.stop()
        assertNull(status("fake")["rss_bytes"], "stopped")
    }
}
