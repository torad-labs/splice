// One real assembled head admits and prices a model published after it started.
package splice.app.roster

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.core.model.DiscoveredModel
import splice.core.model.ModelRates
import splice.usage.economics.EconomicsRead
import splice.usage.perf.HeadSessionPerfSource
import java.nio.file.Path

private const val JOINED = "synthetic-joined"

@Timeout(60)
class RunningRosterRefreshTest {
    @Test
    fun `hourly publication reaches the same running head and its retained control readers`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val fixture = RunningRosterFixture(tmp, this)
        try {
            withTimeout(30_000) {
                fixture.start()
                assertFalse(JOINED in fixture.headGet("/v1/models").body())
                assertTrue("synthetic-joined" in fixture.statusline(JOINED).body())
                val port = fixture.managed.head.port
                fixture.models += DiscoveredModel(
                    JOINED, "Joined synthetic", 256_000,
                    rates = ModelRates(input = 2.0, cacheRead = 0.5, output = 8.0),
                )
                fixture.refresh()
                assertEquals(port, fixture.managed.head.port, "the head never restarted")
                assertDiscovery(fixture)
                assertPricedTurn(fixture)
                assertBoard(fixture)
                assertTeam(fixture)
                assertTrue("Joined synthetic" in fixture.statusline(JOINED).body())
                val line = fixture.statusline(JOINED).body().replace(Regex("\\u001B\\[[0-9;]*m"), "")
                assertTrue("$10.00" in line, line)
                fixture.models = emptyList()
                fixture.refresh()
                assertFalse(JOINED in fixture.headGet("/v1/models").body())
                assertEquals(400, fixture.turn(JOINED).statusCode())
                assertEquals(1, fixture.requests.size, "removed discovery cannot reach the upstream")
                assertTrue(checkNotNull(fixture.managed.statusline.catalog).contains("synthetic-original"))
            }
        } finally {
            fixture.close()
        }
    }

    private fun assertDiscovery(fixture: RunningRosterFixture) {
        val response = fixture.headGet("/v1/models")
        assertEquals(200, response.statusCode(), response.body())
        val rows = Json.parseToJsonElement(response.body()).jsonObject.getValue("data").jsonArray
        val row = rows.singleOrNull { it.jsonObject.getValue("id").jsonPrimitive.content.endsWith(JOINED) }?.jsonObject
        assertNotNull(row, "the running head must list the joined model: ${response.body()}")
        assertEquals("Joined synthetic", checkNotNull(row).getValue("display_name").jsonPrimitive.content)
    }

    private fun assertPricedTurn(fixture: RunningRosterFixture) {
        val turn = fixture.turn(JOINED)
        assertEquals(200, turn.statusCode(), turn.body())
        assertTrue("message_stop" in turn.body(), turn.body())
        val request = Json.parseToJsonElement(fixture.requests.single()).jsonObject
        assertEquals(JOINED, request["model"]?.jsonPrimitive?.content)
        val perf = fixture.managed.sources.perf as HeadSessionPerfSource
        val total = checkNotNull(perf.sessionTotal("synthetic-session")).models.getValue(JOINED)
        assertEquals(10.0, total.usd)
        assertEquals(0L, total.gaps.unpricedTurns)
        val read = checkNotNull(fixture.managed.sources.economics).read()
        val bucket = assertInstanceOf(EconomicsRead.Rows::class.java, read).rows.single()
        assertEquals(10.0, bucket.cost.costUsd)
        assertEquals(0L, bucket.cost.unpricedTurns)
    }

    private fun assertTeam(fixture: RunningRosterFixture) {
        val response = fixture.controlGet("/api/teams/${fixture.teamId}/economics")
        assertEquals(200, response.statusCode(), response.body())
        val slot = Json.parseToJsonElement(response.body()).jsonObject["slots"]!!.jsonArray.single().jsonObject
        assertEquals("10.0", slot.getValue("cost_usd").jsonPrimitive.content)
        assertEquals("0", slot.getValue("unpriced_turns").jsonPrimitive.content)
    }

    private fun assertBoard(fixture: RunningRosterFixture) {
        val response = fixture.controlGet("/api/models")
        assertEquals(200, response.statusCode(), response.body())
        val head = Json.parseToJsonElement(response.body()).jsonObject.getValue("heads").jsonArray.single().jsonObject
        val model = head.getValue("models").jsonArray.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == JOINED
        }.jsonObject
        assertEquals("Joined synthetic", model.getValue("label").jsonPrimitive.content)
        assertEquals("256000", model.getValue("context_window").jsonPrimitive.content)
        assertEquals("2.0", model.getValue("rates").jsonObject.getValue("input").jsonPrimitive.content)
    }
}
