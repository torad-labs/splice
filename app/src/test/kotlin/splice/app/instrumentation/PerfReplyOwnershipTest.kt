package splice.app.instrumentation

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.control.perf.PerfHttpWork
import splice.app.observability.PerfCleanup
import splice.core.util.LogSink
import splice.core.util.PerfWorkBudget
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class PerfReplyOwnershipTest {
    @Test
    fun `expired nested cursors return gone and preserve partial head metadata through encoding`() = testApplication {
        val replies = mapOf(
            "rows" to """{"status":"partial","heads":
                {"one":{"status":"expired"},"two":{"status":"complete"}},"rows":[]}""",
            "events" to """{"status":"partial","metadata":
                {"status":"expired","reason":"cursor_retained_bytes_missing"}}""",
            "partial" to """{"status":"partial","heads":{"one":{"status":"busy"},"two":{"status":"complete"}}}""",
        ).mapValues { Json.parseToJsonElement(it.value).jsonObject }
        val work = PerfHttpWork()
        val before = PerfWorkBudget.retainedBytes()
        application {
            routing {
                replies.forEach { (path, body) ->
                    get("/$path") {
                        work.execute(call) {
                            work.respond(call, null) {
                                requireNotNull(PerfWorkBudget.acquire(1024)).retain(body, 1024)
                                body
                            }
                        }
                    }
                }
            }
        }
        replies.forEach { (path, body) ->
            val response = client.get("/$path")
            assertEquals(if (path == "partial") HttpStatusCode.OK else HttpStatusCode.Gone, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertEquals(body, Json.parseToJsonElement(response.bodyAsText()))
        }
        assertEquals(before, PerfWorkBudget.retainedBytes())
    }

    @Test
    fun `ordinary close failure records the owner and later cleanup still runs`() {
        val cleanup = PerfCleanup(LogSink {})
        cleanup.run("first") { throw IOException("fixture") }
        var lastClosed = false
        cleanup.run("last") { lastClosed = true }
        assertTrue(lastClosed)
        assertEquals(listOf("cleanup_first"), cleanup.issues())
    }

    @Test
    fun `cleanup preserves cancellation and fatal errors`() {
        val cleanup = PerfCleanup(LogSink {})
        assertThrows(CancellationException::class.java) {
            cleanup.run("cancelled") { throw CancellationException("fixture") }
        }
        assertThrows(AssertionError::class.java) { cleanup.run("fatal") { throw AssertionError("fixture") } }
    }

    @Test
    fun `fatal or cancelled teardown releases later owned memory and preserves the first failure`() {
        val cleanup = PerfCleanup(LogSink {})
        for (failure in listOf(CancellationException("cancelled"), AssertionError("fatal"))) {
            val before = PerfWorkBudget.retainedBytes()
            val first = checkNotNull(PerfWorkBudget.acquire(1024))
            val last = checkNotNull(PerfWorkBudget.acquire(1024))
            val thrown = assertThrows(failure.javaClass) {
                cleanup.finish(
                    { first.close(); throw failure },
                    { throw IOException("later failure") },
                    { last.close() },
                )
            }
            assertSame(failure, thrown)
            assertEquals(before, PerfWorkBudget.retainedBytes())
            assertEquals("later failure", thrown.suppressed.single().message)
        }
    }
}
