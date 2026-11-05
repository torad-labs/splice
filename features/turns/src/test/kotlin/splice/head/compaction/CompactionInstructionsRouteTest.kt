package splice.head.compaction

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.compaction.CompactionConfig
import splice.core.compaction.CompactionInstructions
import splice.core.compaction.CompactionModelConfig
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import java.nio.file.Files
import java.nio.file.Path

class CompactionInstructionsRouteTest {
    @Test
    fun `model files are reported with live length and unreadable status without leaking another model`(
        @TempDir dir: Path,
    ) = testApplication {
        Files.writeString(dir.resolve("summary.txt"), "continue carefully")
        val table = CompactionInstructions(
            CompactionConfig(
                model = listOf(
                    CompactionModelConfig("astra", file = "summary.txt"),
                    CompactionModelConfig("sol", file = "missing.txt"),
                    CompactionModelConfig("astra-other", file = "summary.txt"),
                ),
            ),
            dir,
            log = {},
        )
        val head = TurnsHead(
            "claudex",
            object : HeadCompactSource {
                override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
            },
            ModelCatalog(
                "claude-codex--",
                listOf(ModelEntry("astra", contextWindow = 400_000), ModelEntry("sol", contextWindow = 400_000)),
                400_000,
            ),
        )
        val route = CompactionInstructionsRoute(TurnsHeadLookup { listOf(head) })
        application {
            routing {
                get("/instructions") { route.instructions(call, table) }
            }
        }
        val response = client.get("/instructions?head=claudex")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val scopes = Json.parseToJsonElement(body).jsonObject.getValue("scopes").jsonArray
        assertEquals(2, scopes.size)
        val readable = scopes.single {
            it.jsonObject.getValue("source").jsonPrimitive.content.startsWith("model:astra ")
        }.jsonObject
        assertEquals("continue carefully".length, readable.getValue("chars").jsonPrimitive.int)
        val missing = scopes.single { it.jsonObject.getValue("source").jsonPrimitive.content.startsWith("model:sol ") }
            .jsonObject
        assertEquals(JsonNull, missing.getValue("chars"))
        assertTrue("unreadable" in missing.getValue("source").jsonPrimitive.content)
        assertFalse("continue carefully" in body)
        assertFalse("astra-other" in body)
    }
}
