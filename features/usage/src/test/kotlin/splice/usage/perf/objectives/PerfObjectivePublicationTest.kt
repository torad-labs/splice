package splice.usage.perf.objectives

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class PerfObjectivePublicationTest {
    @Test
    fun `settings winning the publication boundary reject old lifecycle without changing durable history`(
        @TempDir tmp: Path,
    ) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure(objectiveSettings(hold = 0))
            val event = fixture.evaluate(120_000).event()
            val journal = AlertJournal(fixture.store.lifecyclePath(), true)
            val revision = requireNotNull(event.text("settings_revision"))
            fixture.configure(objectiveSettings(hold = 0, threshold = 300.0))
            val result = fixture.store.publishIfCurrent(revision) {
                journal.publish(journal.data.copy(events = emptyList()))
            }
            assertEquals(ObjectivePublicationResult.SETTINGS_CHANGED, result)
            fixture.restart()
            val events = takeReply(fixture.engine.history(0, 200_000))["events"] as JsonArray
            assertEquals(event, events.single().jsonObject)
        }
    }

    @Test
    fun `settings update cannot complete between the generation check and durable lifecycle publication`(
        @TempDir tmp: Path,
    ) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure(objectiveSettings(hold = 0))
            val event = fixture.evaluate(120_000).event()
            val journal = AlertJournal(fixture.store.lifecyclePath(), true)
            val entered = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val updating = CountDownLatch(1)
            Executors.newFixedThreadPool(2).use { workers ->
                val publishing = workers.submit<ObjectivePublicationResult> {
                    fixture.store.publishIfCurrent(requireNotNull(event.text("settings_revision"))) {
                        entered.countDown()
                        check(finish.await(5, TimeUnit.SECONDS))
                        journal.publish(journal.data.copy(lastEvaluation = 130_000))
                    }
                }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val update = workers.submit<kotlinx.serialization.json.JsonObject> {
                        updating.countDown()
                        fixture.configure(objectiveSettings(hold = 0, threshold = 300.0))
                    }
                    assertTrue(updating.await(5, TimeUnit.SECONDS))
                    assertThrows(TimeoutException::class.java) { update.get(100, TimeUnit.MILLISECONDS) }
                    finish.countDown()
                    assertEquals(ObjectivePublicationResult.PUBLISHED, publishing.get(5, TimeUnit.SECONDS))
                    assertEquals("ok", update.get(5, TimeUnit.SECONDS).text("status"))
                } finally {
                    finish.countDown()
                }
            }
            fixture.restart()
            val status = takeReply(fixture.engine.status())
            assertEquals(130_000L, status.long("as_of_ms"))
            assertEquals(event.text("settings_revision"), status.event().text("settings_revision"))
            assertTrue(takeReply(fixture.store.settings()).text("revision") != event.text("settings_revision"))
        }
    }
}
