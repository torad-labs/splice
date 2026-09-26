// V4-299: PATCH /api/config said persisted: state/config.json whatever the write did, so a knob that did
// not save read as saved to the caller and reverted at the next start. Driven through a real
// ControlServer under the bearer, with the state directory made unwritable for the one request. Its own
// class since the arm pushed ControlServerTest past detekt's LargeClass ceiling (CI run 36243638197).
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigPersistRouteTest {

    private lateinit var control: ControlServer
    private lateinit var key: String
    private lateinit var statePaths: StatePaths
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun setUp() {
        statePaths = StatePaths(baseOverride = Files.createTempDirectory("config-persist").resolve("state"))
        val mgmt = MgmtKey(statePaths)
        key = mgmt.get()
        control = ControlServer(
            port = 0,
            heads = emptyMap(),
            config = ConfigService(statePaths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = {},
        )
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    @Test
    fun `a config patch whose write fails says it did not persist and why - V4-299`() = runBlocking<Unit> {
        val stateDir = statePaths.configFile.parent
        Files.setPosixFilePermissions(stateDir, PosixFilePermissions.fromString("r-x------"))
        val body = try {
            client.patch("http://127.0.0.1:${control.listeningPort}/api/config") {
                header("Authorization", "Bearer $key")
                header("Content-Type", "application/json")
                setBody("""{"effort":"low"}""")
            }.bodyAsText()
        } finally {
            Files.setPosixFilePermissions(stateDir, PosixFilePermissions.fromString("rwx------"))
        }
        val obj = json.parseToJsonElement(body).jsonObject
        assertEquals("low", obj["applied"]!!.jsonObject["effort"]?.jsonPrimitive?.content, "it still applies: $body")
        assertEquals(JsonNull, obj["persisted"], body)
        assertTrue("could not be written" in obj["not_persisted"]?.jsonPrimitive?.content.orEmpty(), body)
    }
}
