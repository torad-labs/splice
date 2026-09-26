package splice.heads.start

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.heads.HeadAudit
import splice.heads.HeadOperations
import splice.heads.HeadResolver
import splice.heads.HeadStatusListing
import splice.heads.HeadTarget
import splice.heads.ListHeads

class HeadOperationsTest {
    @Test
    fun `stops the resolved instance before auditing and projecting its live status`() = testApplication {
        val events = mutableListOf<String>()
        val operations = operations(events, RecordingTarget(events, running = true))
        application {
            routing {
                post("/api/heads/{head}/{action}") { operations.action(call) }
            }
        }

        val response = client.post("/api/heads/claudex/stop")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"running\":false}", response.bodyAsText())
        assertEquals(listOf("resolve:claudex", "stop", "audit:claudex:stop", "status"), events)
    }

    @Test
    fun `restarts the resolved instance before auditing and projecting its live status`() = testApplication {
        val events = mutableListOf<String>()
        val operations = operations(events, RecordingTarget(events, running = false))
        application {
            routing {
                post("/api/heads/{head}/{action}") { operations.action(call) }
            }
        }

        val response = client.post("/api/heads/claudex/restart")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"running\":true}", response.bodyAsText())
        assertEquals(listOf("resolve:claudex", "restart", "audit:claudex:restart", "status"), events)
    }

    @Test
    fun `an unknown action resolves first then preserves its 400 without effects or audit`() = testApplication {
        val events = mutableListOf<String>()
        val operations = operations(events, RecordingTarget(events, running = true))
        application {
            routing {
                post("/api/heads/{head}/{action}") { operations.action(call) }
            }
        }

        val response = client.post("/api/heads/claudex/inspect")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("{\"error\":\"unknown action\"}", response.bodyAsText())
        assertEquals(listOf("resolve:claudex"), events)
    }

    @Test
    fun `a lookup refusal prevents a stop effect and its audit`() = testApplication {
        val audited = mutableListOf<String>()
        val operations = HeadOperations(
            HeadResolver { call, _ ->
                call.respondText(
                    "{\"error\":\"unknown head\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
                null
            },
            HeadAudit { name, _ -> audited.add(name) },
        )
        application {
            routing {
                post("/api/heads/{head}/{action}") { operations.action(call) }
            }
        }

        val response = client.post("/api/heads/missing/stop")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("{\"error\":\"unknown head\"}", response.bodyAsText())
        assertEquals(emptyList<String>(), audited)
    }

    @Test
    fun `reads the resolved head tail before returning its established log payload`() = testApplication {
        val events = mutableListOf<String>()
        val operations = operations(
            events,
            RecordingTarget(events, running = true, logs = "first\n\nsecond\n", path = "/logs/codex.log"),
        )
        application {
            routing {
                get("/api/logs/{head}") { operations.logsJson(call, 42) }
            }
        }

        val response = client.get("/api/logs/claude-codex")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            "{\"key\":\"claude-codex\",\"path\":\"/logs/codex.log\",\"lines\":[\"first\",\"second\"]}",
            response.bodyAsText(),
        )
        assertEquals(listOf("resolve:claude-codex", "tail:42", "path"), events)
    }

    @Test
    fun `lists every supplied head snapshot in registry order`() = testApplication {
        val events = mutableListOf<String>()
        val listHeads = ListHeads(
            HeadStatusListing {
                events.add("list")
                listOf(
                    buildJsonObject { put("key", "codex") },
                    buildJsonObject { put("key", "kimi") },
                )
            },
        )
        application {
            routing {
                get("/api/heads") { listHeads.handle(call) }
            }
        }

        val response = client.get("/api/heads")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"heads\":[{\"key\":\"codex\"},{\"key\":\"kimi\"}]}", response.bodyAsText())
        assertEquals(listOf("list"), events)
    }

    private fun operations(events: MutableList<String>, target: HeadTarget): HeadOperations = HeadOperations(
        HeadResolver { _, name ->
            events.add("resolve:$name")
            target
        },
        HeadAudit { name, action -> events.add("audit:$name:$action") },
    )

    private class RecordingTarget(
        private val events: MutableList<String>,
        private var running: Boolean,
        private val logs: String = "",
        private val path: String = "",
    ) : HeadTarget {
        override suspend fun start() {
            events.add("start")
            running = true
        }

        override suspend fun stop() {
            events.add("stop")
            running = false
        }

        override suspend fun restart() {
            events.add("restart")
            running = true
        }

        override fun status(): JsonObject {
            events.add("status")
            return buildJsonObject { put("running", running) }
        }

        override fun tailLogs(tail: Int): String {
            events.add("tail:$tail")
            return logs
        }

        override fun logPath(): String {
            events.add("path")
            return path
        }
    }
}
