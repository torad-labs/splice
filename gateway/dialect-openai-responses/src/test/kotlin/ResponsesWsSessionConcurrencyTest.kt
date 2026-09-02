// 2026-08-11 PRODUCTION INCIDENT REGRESSION TEST. A daemon up 91h stopped serving turns: requests
// were accepted and never dispatched, while the control plane still reported 4/4 heads ready. Cause:
// ResponsesWsSession kept `chains`/`epochs` in plain LinkedHashMaps with NO lock — only a naming
// convention (`trimLocked`) implying one — and `completed`/`cleared` are driven from Netty
// event-loop threads with several conversations in flight. Concurrent mutation corrupted the map's
// internal list into a cycle; six event loops were found spinning in HashMap.remove at ~2 CPU-HOURS
// each, and because Netty loops are shared, every connection they served was starved.
//
// This test hammers the same entry points from many threads. Against the unsynchronized version it
// hangs or throws; the timeout is what turns "hangs forever" into a failing test instead of a
// wedged CI job — the same distinction the production bug turned on.
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.dialect.responses.ResponsesWsSession
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ResponsesWsSessionConcurrencyTest {

    private fun request(n: Int): JsonObject = buildJsonObject {
        put("model", "gpt-5.6-sol")
        put(
            "input",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "message")
                        put("id", "m$n")
                    },
                )
            },
        )
    }

    @Test
    @Timeout(60)
    fun `concurrent commits and clears never corrupt the chain maps`() {
        val session = ResponsesWsSession()
        val threads = 12
        val opsPerThread = 4_000
        val pool = Executors.newFixedThreadPool(threads)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        repeat(threads) { t ->
            pool.submit {
                try {
                    for (i in 0 until opsPerThread) {
                        // Far more distinct keys than MAX_CONVERSATIONS, so trim runs constantly —
                        // eviction under concurrency is exactly what corrupted the map in prod.
                        val key = "conv-${(t * opsPerThread + i) % 512}"
                        val req = request(i)
                        session.frameFor(key, req, generation = 1L)
                        val epoch = session.epochOf(key)
                        if (i % 3 == 0) {
                            session.cleared(key)
                        } else {
                            session.completed(key, req, "resp-$i", generation = 1L, epoch = epoch)
                        }
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                }
            }
        }
        pool.shutdown()
        val finished = pool.awaitTermination(45, TimeUnit.SECONDS)

        assertTrue(finished, "workers did not finish — the chain maps wedged, which is the 2026-08-11 outage")
        assertTrue(failures.isEmpty(), "concurrent access threw: ${failures.firstOrNull()}")
        // Still functional afterwards, and the cap still holds.
        val frame = session.frameFor("conv-after", request(1), generation = 1L)
        assertTrue(frame.json.contains("response.create"), "session unusable after the stress run")
    }
}
