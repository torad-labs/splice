// NEW: V4-440 — refresh waits an hour, repeats after provider failures, and stops with the daemon scope.
package splice.app.v4440

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.HeadModelsSource
import splice.app.provider.ModelRosters
import splice.core.config.StatePaths
import splice.core.model.DiscoveredModel
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.models.discovery.Discovery
import splice.upstream.Ticker
import java.nio.file.Path

private class ControlledTick : Ticker {
    val asked = Channel<Long>(Channel.UNLIMITED)
    val next = Channel<Boolean>(Channel.UNLIMITED)
    override suspend fun awaitTick(intervalMs: Long): Boolean {
        asked.send(intervalMs)
        return next.receive()
    }
}

class RosterRefreshTimerTest {
    private suspend fun refresh(asked: Channel<Unit>, tick: ControlledTick) {
        tick.next.send(true)
        asked.receive()
        tick.asked.receive() // publication precedes asking for the next tick
    }

    @Test
    fun `hourly refresh continues after a failure and cancels while awaiting the next hour`(@TempDir tmp: Path) =
        runBlocking {
            val provider = ProviderConfig(
                Dialect.OPENAI_CHAT,
                "https://synthetic.example.test/v1",
                AuthConfig("api-key", env = "SYNTHETIC_KEY"),
            )
            val tick = ControlledTick()
            var models = listOf(DiscoveredModel("synthetic-first"))
            var fail = false
            val asked = Channel<Unit>(Channel.UNLIMITED)
            val rosters = ModelRosters(
                StatePaths(baseOverride = tmp),
                {},
                HeadModelsSource { _, _ ->
                    asked.trySend(Unit)
                    if (fail) {
                        Discovery.Unavailable(null, "synthetic unavailable")
                    } else {
                        Discovery.Found("https://synthetic.example.test/v1/models", models)
                    }
                },
                ticker = tick,
            )
            rosters.resolve(mapOf("synthetic" to provider))
            asked.receive()
            val job = rosters.start(this, mapOf("synthetic" to provider))
            try {
                withTimeout(5_000) {
                    assertEquals(3_600_000L, tick.asked.receive())
                    assertTrue(asked.tryReceive().isFailure, "no immediate duplicate request after startup")
                    models = models + DiscoveredModel("synthetic-second")
                    refresh(asked, tick) // publication precedes asking for the next tick
                    assertEquals(models, rosters.forHead("synthetic"))
                    fail = true
                    refresh(asked, tick)
                    assertEquals(models, rosters.forHead("synthetic"))
                    fail = false
                    models = models + DiscoveredModel("synthetic-third")
                    refresh(asked, tick)
                    assertEquals(models, rosters.forHead("synthetic"))
                }
            } finally {
                job.cancel()
                job.join()
            }
            assertTrue(job.isCancelled)
        }
}
