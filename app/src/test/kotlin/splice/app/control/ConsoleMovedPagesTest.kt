// NEW: Nov 19, 2026 — the console's old pages whose content moved into the Night Deck pages: an old link or bookmark
// to Requests, Usage, Models or Compare lands on the page that took that content, its query kept. Proven over real HTTP
// against a real ControlServer, reading the redirect it answers without following it.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleMovedPagesTest {

    private val client = HttpClient(CIO) {
        expectSuccess = false
        followRedirects = false
    }
    private lateinit var control: ControlServer

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) {
        val paths = StatePaths(baseOverride = tempDir.resolve("state"))
        control = controlServerFor(
            port = 0,
            heads = emptyMap(),
            config = ConfigService(paths),
            auth = ControlAuth(mgmtKey = MgmtKey(paths, log = {}), log = {}),
        )
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    private fun movedTo(path: String): String? = runBlocking {
        val reply = client.get("http://127.0.0.1:${control.listeningPort}$path")
        assertTrue(reply.status.value in 300..399, "$path answers a redirect, not ${reply.status}")
        reply.headers[HttpHeaders.Location]
    }

    @Test
    fun `Requests and Usage land on Activity with their query`() {
        assertEquals("/activity.html?session=aaaa1111", movedTo("/requests.html?session=aaaa1111"))
        assertEquals("/activity.html?win=30d&account=work", movedTo("/usage.html?win=30d&account=work"))
        assertEquals("/activity.html", movedTo("/requests.html"))
    }

    @Test
    fun `Models lands on Providers with its command, and Compare on Providers' Compare models`() {
        assertEquals("/providers.html?cmd=claude-acme", movedTo("/models.html?cmd=claude-acme"))
        assertEquals("/providers.html?open=compare", movedTo("/compare.html"))
    }

    @Test
    fun `the bare address still opens Accounts`() {
        assertEquals("/accounts.html", movedTo("/"))
    }
}
