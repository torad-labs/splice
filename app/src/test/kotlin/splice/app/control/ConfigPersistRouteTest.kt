// V4-299: PATCH /api/config said persisted: state/config.json whatever the write did, so a knob that did
// not save read as saved to the caller and reverted at the next start. Driven through a real
// ControlServer under the bearer, with the state directory made unwritable for the one request. Its own
// class since the arm pushed ControlServerTest past detekt's LargeClass ceiling (CI run 36243638197).
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigPersistRouteTest {

    private lateinit var control: ControlServer
    private lateinit var key: String
    private lateinit var statePaths: StatePaths
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) {
        statePaths = StatePaths(baseOverride = tempDir.resolve("state"))
        val mgmt = MgmtKey(statePaths)
        key = mgmt.get()
        control = controlServerFor(
            port = 0,
            heads = emptyMap(),
            config = ConfigService(statePaths),
            auth = ControlAuth(mgmtKey = mgmt, log = {}),
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

    private suspend fun patchConfig(body: String) = json.parseToJsonElement(
        client.patch("http://127.0.0.1:${control.listeningPort}/api/config") {
            header("Authorization", "Bearer $key")
            header("Content-Type", "application/json")
            setBody(body)
        }.bodyAsText(),
    ).jsonObject

    @Test
    fun `config patch flags a restart-only knob as restart_required and not a live knob`() = runBlocking<Unit> {
        val restartOnly = patchConfig("""{"effort":"low"}""")
        assertEquals(listOf("effort"), restartOnly["restart_required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val live = patchConfig("""{"maxInflight":7,"maxQueued":99}""")
        assertEquals(2, live["applied"]!!.jsonObject.size, live.toString())
        assertEquals(0, live["restart_required"]!!.jsonArray.size, live.toString())
        // Live means the next read already sees it: the effective view is what admission reads per request.
        val read = client.get("http://127.0.0.1:${control.listeningPort}/api/config") {
            header("Authorization", "Bearer $key")
        }.bodyAsText()
        val effective = json.parseToJsonElement(read).jsonObject["effective"]!!.jsonObject
        assertEquals("7", effective["maxInflight"]?.jsonPrimitive?.content)
        assertEquals("99", effective["maxQueued"]?.jsonPrimitive?.content)
    }
}
