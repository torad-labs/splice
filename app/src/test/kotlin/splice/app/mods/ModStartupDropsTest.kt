package splice.app.mods

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.mods.handover.MOD_HANDOVER_BATCH_SIZE
import splice.app.mods.handover.MOD_HANDOVER_CAPACITY
import splice.app.mods.handover.ModDelivery
import splice.app.mods.handover.ModHandoverBatch
import splice.app.mods.run.ModEvent
import java.nio.file.Path

class ModStartupDropsTest {
    @TempDir lateinit var root: Path

    @Test
    fun `when staged starts run out of room the catalog counts each missed start`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            openWhileBooting(rig)
            val excess = 17
            repeat(MOD_HANDOVER_CAPACITY + excess) { rig.successorTurn("staged-$it") }
            HttpClient(CIO).use { client ->
                assertEquals(excess.toLong(), client.catalog(rig)["handoverDropped"]?.jsonPrimitive?.longOrNull ?: 0L)
            }
        }
    }

    @Test
    fun `a slow mod's dropped count plus its deliveries equals the events accepted during startup`() = runBlocking {
        ModHandoverTestRig(root).use { rig ->
            openWhileBooting(rig)
            val offered = 2400
            HttpClient(CIO).use { client ->
                (0 until offered).chunked(MOD_HANDOVER_BATCH_SIZE).forEach { ids ->
                    val batch = ModHandoverBatch(ids.map(::delivery))
                    val response = client.post(rig.handoverUrl) {
                        bearerAuth(rig.key.get())
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(batch))
                    }
                    assertEquals(HttpStatusCode.NoContent, response.status)
                }
                val catalog = client.catalog(rig)
                val losses = catalog.getValue("handoverDropped").jsonPrimitive.long
                val mod = catalog.getValue("mods").jsonArray.single().jsonObject
                assertTrue(losses > 0)
                assertEquals(losses, mod.getValue("dropped").jsonPrimitive.long)
                rig.recoverSessions()
                val delivered = rig.recordedStartups(offered - losses.toInt())
                assertEquals(offered.toLong(), delivered.size + losses)
            }
        }
    }

    private suspend fun openWhileBooting(rig: ModHandoverTestRig) {
        rig.installRecorder()
        rig.retire(leave = false)
        rig.openSuccessor()
        rig.releaseRelay()
        rig.awaitConfirmations()
    }

    private fun delivery(id: Int): ModDelivery {
        val session = "startup-$id"
        return ModDelivery(ModEvent("turn.complete", session, JsonObject(mapOf("session" to JsonPrimitive(session)))))
    }

    private suspend fun HttpClient.catalog(rig: ModHandoverTestRig): JsonObject {
        val response = get(rig.modsUrl) { bearerAuth(rig.key.get()) }
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }
}
