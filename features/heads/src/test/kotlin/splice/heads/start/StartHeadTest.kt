package splice.heads.start

import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StartHeadTest {
    @Test
    fun `starts the resolved instance before auditing and projecting its live status`() = testApplication {
        val events = mutableListOf<String>()
        var running = false
        val target = object : StartHeadTarget {
            override suspend fun start() {
                events.add("start")
                running = true
            }

            override fun status(): JsonObject {
                events.add("status")
                return buildJsonObject {
                    put("key", "codex")
                    put("running", running)
                }
            }
        }
        val start = StartHead(
            StartHeadResolver { _, name ->
                events.add("resolve:$name")
                target
            },
            StartHeadAudit { name -> events.add("audit:$name") },
        )
        application {
            routing {
                post("/api/heads/{head}/start") { start.handle(call) }
            }
        }

        val response = client.post("/api/heads/claudex/start")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"key\":\"codex\",\"running\":true}", response.bodyAsText())
        assertEquals(listOf("resolve:claudex", "start", "audit:claudex", "status"), events)
    }

    @Test
    fun `a lookup refusal keeps its response and never audits a start`() = testApplication {
        val audited = mutableListOf<String>()
        val start = StartHead(
            StartHeadResolver { call, _ ->
                call.respondText(
                    "{\"error\":\"unknown head\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
                null
            },
            StartHeadAudit { name -> audited.add(name) },
        )
        application {
            routing {
                post("/api/heads/{head}/start") { start.handle(call) }
            }
        }

        val response = client.post("/api/heads/missing/start")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("{\"error\":\"unknown head\"}", response.bodyAsText())
        assertEquals(emptyList<String>(), audited)
    }
}
