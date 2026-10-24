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
    fun `a stop stops the head, answers its live status and is audited`() = testApplication {
        val target = RecordingTarget(running = true)
        val audited = mutableListOf<String>()
        val operations = operations(audited, target)
        application {
            routing {
                post("/api/heads/{head}/{action}") { operations.action(call) }
            }
        }

        val response = client.post("/api/heads/claudex/stop")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"running\":false}", response.bodyAsText())
        assertEquals(listOf("claudex:stop"), audited)
    }

    @Test
    fun `a restart brings the head back, answers its live status and is audited`() = testApplication {
        val target = RecordingTarget(running = false)
        val audited = mutableListOf<String>()
        val operations = operations(audited, target)
        application {
            routing {
                post("/api/heads/{head}/{action}") { operations.action(call) }
            }
        }

        val response = client.post("/api/heads/claudex/restart")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"running\":true}", response.bodyAsText())
        assertEquals(listOf("claudex:restart"), audited)
    }

    @Test
    fun `an unknown action is a 400 that never moves the head and audits nothing`() = testApplication {
        val target = RecordingTarget(running = true)
        val audited = mutableListOf<String>()
        val operations = operations(audited, target)
        application {
            routing {
                post("/api/heads/{head}/{action}") { operations.action(call) }
            }
        }

        val response = client.post("/api/heads/claudex/inspect")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("{\"error\":\"unknown action\"}", response.bodyAsText())
        assertEquals(true, target.isRunning(), "an unknown action must not move the head")
        assertEquals(emptyList<String>(), audited)
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
    fun `the log route answers the resolved head's tail, blank lines dropped`() = testApplication {
        val target = RecordingTarget(running = true, logs = "first\n\nsecond\n", path = "/logs/codex.log")
        val operations = operations(mutableListOf(), target)
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
        assertEquals(42, target.tailAsked, "the route asks for the tail it was given")
    }

    @Test
    fun `lists every supplied head snapshot in registry order`() = testApplication {
        val listHeads = ListHeads(
            HeadStatusListing {
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
    }

    private fun operations(audited: MutableList<String>, target: HeadTarget): HeadOperations = HeadOperations(
        HeadResolver { _, _ -> target },
        HeadAudit { name, action -> audited.add("$name:$action") },
    )

    private class RecordingTarget(
        private var running: Boolean,
        private val logs: String = "",
        private val path: String = "",
    ) : HeadTarget {
        var tailAsked: Int = 0
            private set

        fun isRunning(): Boolean = running

        override suspend fun start() {
            running = true
        }

        override suspend fun stop() {
            running = false
        }

        override suspend fun restart() {
            running = true
        }

        override fun status(): JsonObject = buildJsonObject { put("running", running) }

        override fun tailLogs(tail: Int): String {
            tailAsked = tail
            return logs
        }

        override fun logPath(): String = path
    }
}
