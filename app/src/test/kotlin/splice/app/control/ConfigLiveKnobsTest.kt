// Spec section 9: a live knob reaches a running session without a restart, and each knob's answer carries its
// effective value, the layer it came from and whether it is live or restart-only. Driven through a real
// ControlServer, with an admission gate built the way a head builds its own: reading the limit from the same
// ConfigService on every admission.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.knobsByKey
import splice.upstream.retry.InflightGate
import java.nio.file.Path

private const val WAIT_MS = 5_000L

class ConfigLiveKnobsTest {

    private lateinit var control: ControlServer
    private lateinit var config: ConfigService
    private lateinit var key: String
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        val statePaths = StatePaths(baseOverride = tempDir.resolve("state"))
        val mgmt = MgmtKey(statePaths)
        key = mgmt.get()
        config = ConfigService(statePaths)
        control = controlServerFor(
            port = 0,
            heads = emptyMap(),
            config = config,
            auth = ControlAuth(mgmtKey = mgmt, log = {}),
        )
        runBlocking { control.start() }
    }

    @AfterEach
    fun tearDown() {
        control.stop()
        client.close()
    }

    private suspend fun patch(body: String): String =
        client.patch("http://127.0.0.1:${control.listeningPort}/api/config") {
            header("Authorization", "Bearer $key")
            header("Content-Type", "application/json")
            setBody(body)
        }.bodyAsText()

    private suspend fun read(): String = client.get("http://127.0.0.1:${control.listeningPort}/api/config") {
        header("Authorization", "Bearer $key")
    }.bodyAsText()

    @Test
    fun `a limit raised over the control route admits the turn already waiting, with no restart`() = runBlocking<Unit> {
        patch("""{"maxInflight":1}""")
        val gate = InflightGate(maxInflight = { config.getConfig("h").maxInflight }, maxQueued = { 4 })
        val running = gate.acquire() as InflightGate.Admission.Acquired
        val waiting = async { gate.acquire() }
        withTimeout(WAIT_MS) { while (gate.snapshot().queued < 1) yield() }
        assertFalse(waiting.isCompleted, "one slot is held, so the second turn waits")

        val answer = json.parseToJsonElement(patch("""{"maxInflight":2}""")).jsonObject

        assertEquals(0, answer.getValue("restart_required").jsonArray.size, answer.toString())
        // The raise is taken up by the next admission call, which drains the queue under the new limit.
        val newcomer = async { gate.acquire() }
        val second = withTimeout(WAIT_MS) { waiting.await() } as InflightGate.Admission.Acquired
        assertFalse(newcomer.isCompleted, "two slots are now held, so the newcomer waits behind it")
        second.slot.release()
        (withTimeout(WAIT_MS) { newcomer.await() } as InflightGate.Admission.Acquired).slot.release()
        running.slot.release()
    }

    @Test
    fun `each knob answers its effective value, the layer it came from and live or restart`() = runBlocking<Unit> {
        val answer = json.parseToJsonElement(patch("""{"maxInflight":9,"effort":"low"}""")).jsonObject
        assertEquals(listOf("effort"), answer.getValue("restart_required").jsonArray.map { it.jsonPrimitive.content })

        val view = json.parseToJsonElement(read()).jsonObject
        val effective = view.getValue("effective").jsonObject
        val runtime = view.getValue("layers").jsonObject.getValue("runtime").jsonObject
        val restartOnly = view.getValue("restart_required_keys").jsonArray.map { it.jsonPrimitive.content }

        assertEquals("9", effective.getValue("maxInflight").jsonPrimitive.content)
        assertEquals("9", runtime.getValue("maxInflight").jsonPrimitive.content, "its source is the runtime layer")
        assertFalse("maxInflight" in restartOnly, "a live knob is not listed as restart-only")
        assertEquals("low", effective.getValue("effort").jsonPrimitive.content)
        assertEquals("low", runtime.getValue("effort").jsonPrimitive.content)
        assertTrue("effort" in restartOnly, "a restart-only knob is listed as such")
    }

    @Test
    fun `the usage warning thresholds are live, so no restart is named and a head reads them`() = runBlocking<Unit> {
        val answer = json.parseToJsonElement(patch("""{"usageWarnPct":90,"usageWarnTokens5h":5000}""")).jsonObject

        assertEquals(0, answer.getValue("restart_required").jsonArray.size, answer.toString())
        val view = json.parseToJsonElement(read()).jsonObject
        assertEquals("90", view.getValue("effective").jsonObject.getValue("usageWarnPct").jsonPrimitive.content)
        val restartOnly = view.getValue("restart_required_keys").jsonArray.map { it.jsonPrimitive.content }
        assertFalse("usageWarnPct" in restartOnly || "usageWarnTokens5h" in restartOnly)
        assertEquals(90, config.getConfig("h").usageWarnPct, "a head reads it live, the way its usage view does")
    }

    @Test
    fun `the request read ceiling is live, so no restart is named and a head reads it`() = runBlocking<Unit> {
        val answer = json.parseToJsonElement(patch("""{"requestReadTimeoutMs":45000}""")).jsonObject

        assertEquals(0, answer.getValue("restart_required").jsonArray.size, answer.toString())
        val view = json.parseToJsonElement(read()).jsonObject
        val restartOnly = view.getValue("restart_required_keys").jsonArray.map { it.jsonPrimitive.content }
        assertFalse("requestReadTimeoutMs" in restartOnly, "a live knob is not listed as restart-only")
        // The head's own answer, which is what its body reader asks at every read (RequestReadBudgetMs).
        assertEquals(45_000L, config.getConfig("h").requestReadTimeoutMs)
    }

    @Test
    fun `the request body cap is live, so no restart is named and a head reads it`() = runBlocking<Unit> {
        val answer = json.parseToJsonElement(patch("""{"maxRequestBytes":8388608}""")).jsonObject

        assertEquals(0, answer.getValue("restart_required").jsonArray.size, answer.toString())
        val view = json.parseToJsonElement(read()).jsonObject
        val restartOnly = view.getValue("restart_required_keys").jsonArray.map { it.jsonPrimitive.content }
        assertFalse("maxRequestBytes" in restartOnly, "a live knob is not listed as restart-only")
        // What both enforcement layers ask for per request: the head's materialization gate and, in front of it,
        // the listener's ingress guard (RequestByteCap).
        assertEquals(8 * 1024 * 1024, config.getConfig("h").maxRequestBytes)
    }

    @Test
    fun `the materialization budget is live, so no restart is named and a head reads it`() = runBlocking<Unit> {
        val answer = json.parseToJsonElement(patch("""{"materializationHeapBytes":536870912}""")).jsonObject

        assertEquals(0, answer.getValue("restart_required").jsonArray.size, answer.toString())
        val view = json.parseToJsonElement(read()).jsonObject
        val restartOnly = view.getValue("restart_required_keys").jsonArray.map { it.jsonPrimitive.content }
        assertFalse("materializationHeapBytes" in restartOnly, "a live knob is not listed as restart-only")
        // What the daemon's one materialization gate asks at every admission (MaterializationBudgetBytes), which it
        // caps into the ceiling the ingress guard in front of each head asks for.
        assertEquals(512 * 1024 * 1024L, config.getConfig().materializationHeapBytes)
    }

    @Test
    fun `the quota poll cadence is live, so no restart is named and a head reads it`() = runBlocking<Unit> {
        val answer = json.parseToJsonElement(patch("""{"quotaPollIntervalMs":600000}""")).jsonObject

        assertEquals(0, answer.getValue("restart_required").jsonArray.size, answer.toString())
        val view = json.parseToJsonElement(read()).jsonObject
        val restartOnly = view.getValue("restart_required_keys").jsonArray.map { it.jsonPrimitive.content }
        assertFalse("quotaPollIntervalMs" in restartOnly, "a live knob is not listed as restart-only")
        // What this head's poller asks between two looks at its account's usage endpoint (QuotaIntervalMs), so an
        // operator who slows a provider down is obeyed by the next wait.
        assertEquals(600_000L, config.getConfig("h").quotaPollIntervalMs)
    }

    @Test
    fun `the MCP request budget is live, so no restart is named and the host reads it`() = runBlocking<Unit> {
        val answer = json.parseToJsonElement(patch("""{"mcpRequestTimeoutMs":60000}""")).jsonObject

        assertEquals(0, answer.getValue("restart_required").jsonArray.size, answer.toString())
        val view = json.parseToJsonElement(read()).jsonObject
        val restartOnly = view.getValue("restart_required_keys").jsonArray.map { it.jsonPrimitive.content }
        assertFalse("mcpRequestTimeoutMs" in restartOnly, "a live knob is not listed as restart-only")
        // Daemon-global, so the hosted MCP server asks the same unkeyed config at every forwarded request
        // (McpRequestBudget) — the other three MCP host values stay the numbers the host was built with.
        assertEquals(60_000L, config.getConfig().asMap()[Knob.MCP_REQUEST_TIMEOUT_MS.key] as? Long)
    }

    @Test
    fun `every knob in the schema is in the config answer with its value, scope and disposition`() = runBlocking<Unit> {
        patch("""{"maxInflight":9,"effort":"low"}""")

        val knobs = json.parseToJsonElement(read()).jsonObject.getValue("knobs").jsonObject

        val missing = Knob.entries.map { it.key }.filter { it !in knobs }
        assertEquals(emptyList<String>(), missing, "a knob the answer does not represent")
        knobs.forEach { (name, view) ->
            val entry = view.jsonObject
            val knob = knobsByKey.getValue(name)
            val disposition = if (knob.restartRequired) "restart" else "live"
            assertEquals(disposition, entry.getValue("disposition").jsonPrimitive.content, name)
            assertTrue(entry.containsKey("value") && entry.containsKey("scope"), "$name: $entry")
            val editable = entry.getValue("editable").jsonPrimitive.boolean
            assertTrue(editable || "read_only_reason" in entry, "$name is read-only with no reason")
        }
        assertEquals("runtime", knobs.getValue("maxInflight").jsonObject.getValue("scope").jsonPrimitive.content)
        assertEquals("9", knobs.getValue("maxInflight").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals("default", knobs.getValue("transcriptView").jsonObject.getValue("scope").jsonPrimitive.content)
        assertEquals("restart", knobs.getValue("effort").jsonObject.getValue("disposition").jsonPrimitive.content)
    }

    @Test
    fun `mirror_reasoning is read-only in the config answer, pinned false with its reason`() = runBlocking<Unit> {
        val knobs = json.parseToJsonElement(read()).jsonObject.getValue("knobs").jsonObject
        val mirror = knobs.getValue(Knob.MIRROR_REASONING.key).jsonObject

        assertFalse(mirror.getValue("editable").jsonPrimitive.boolean, mirror.toString())
        assertEquals(
            "pinned false by project rule; ask Marcos to change",
            mirror.getValue("read_only_reason").jsonPrimitive.content,
        )
        assertEquals("false", mirror.getValue("value").jsonPrimitive.content)
    }

    @Test
    fun `the management key never appears in a config answer`() = runBlocking<Unit> {
        val answers = listOf(patch("""{"maxInflight":3}"""), read())
        answers.forEach { assertFalse(key in it, "the management key leaked into: $it") }
    }
}
