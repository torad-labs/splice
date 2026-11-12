package splice.app.instrumentation

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.app.control.perf.PerfHttpWork
import splice.core.util.PerfWorkPhase

class PerfHttpPhaseAdmissionTest {
    @Test
    fun `status uses an idle active worker while two source reads await checkpoint completion`() = testApplication {
        val work = PerfHttpWork()
        val requested = Channel<Unit>(2)
        application { routing { routes(this, work, requested) } }
        val checkpoint = PerfWorkPhase.checkpoint()
        try {
            coroutineScope {
                val first = async { client.get("/source") }
                val second = async { client.get("/source") }
                withTimeout(5_000L) { repeat(2) { requested.receive() } }
                val status = withTimeout(1_000L) { client.get("/status") }
                assertEquals(HttpStatusCode.OK, status.status)
                checkpoint.close()
                withTimeout(5_000L) {
                    assertEquals(HttpStatusCode.OK, first.await().status)
                    assertEquals(HttpStatusCode.OK, second.await().status)
                }
            }
        } finally {
            checkpoint.close()
            requested.close()
        }
    }

    private fun routes(route: Route, work: PerfHttpWork, requested: Channel<Unit>) {
        val body = buildJsonObject { put("status", "complete") }
        route.get("/source") {
            requested.send(Unit)
            work.source(call) { work.respond(call, null) { body } }
        }
        route.get("/status") {
            work.execute(call) { work.respond(call, null) { body } }
        }
    }
}
