package splice.usage.perf.objectives

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PerfObjectiveStoreTest {
    @Test
    fun `invalid replacement keeps every published objective and rule with stable content free revisions`(
        @TempDir tmp: Path,
    ) {
        val path = tmp.resolve("objectives.json")
        PerfObjectiveStore(path).use { store ->
            val initial = takeReply(store.settings())
            assertEquals("[]", initial["rules"].toString())
            val valid = Json.parseToJsonElement(objectiveSettings()).jsonObject
            assertEquals("ok", takeReply(store.update(valid)).text("status"))
            val revision = store.objective("latency")?.revision
            assertEquals(64, revision?.length)
            val before = takeReply(store.settings())
            val invalid = Json.parseToJsonElement(
                objectiveSettings().replace("\"min_count\":2", "\"min_count\":0"),
            ).jsonObject
            assertEquals("error", takeReply(store.update(invalid)).text("status"))
            assertEquals(before, takeReply(store.settings()))
            assertEquals(mapOf("head" to "h"), store.cohort("latency"))
            assertNull(store.objective("absent"))
            val callerRevision = withRevision(valid, "credentials-never-become-a-revision")
            assertEquals("ok", takeReply(store.update(callerRevision)).text("status"))
            assertEquals(before, takeReply(store.settings()))
            val changed = Json.parseToJsonElement(
                objectiveSettings().replace("\"completion_ms\":100", "\"completion_ms\":150"),
            ).jsonObject
            assertEquals("ok", takeReply(store.update(changed)).text("status"))
            assertNotEquals(revision, store.objective("latency")?.revision)
        }
        PerfObjectiveStore(path).use { reopened -> assertEquals(150.0, reopened.objective("latency")?.completionMs) }
    }

    @Test
    fun `cohort conflicts unknown fields and cumulative sample rates are rejected atomically`(@TempDir tmp: Path) {
        PerfObjectiveStore(tmp.resolve("objectives.json")).use { store ->
            val variants = listOf(
                objectiveSettings().replace("\"cohort\":{}", "\"cohort\":{\"head\":\"other\"}"),
                objectiveSettings(metric = "counters.runtime_gc_pause_ms.per_s"),
                objectiveSettings().replace("\"min_count\":2", "\"minimum_count\":2"),
                objectiveSettings().replace("\"cohort\":{}", "\"cohort\":{\"session\":\"arbitrary\"}"),
            )
            variants.forEach { raw ->
                assertEquals("error", takeReply(store.update(Json.parseToJsonElement(raw).jsonObject)).text("status"))
                assertTrue(takeReply(store.settings())["rules"].toString() == "[]")
            }
        }
    }
}

private fun withRevision(value: JsonObject, revision: String): JsonObject =
    JsonObject(value + ("revision" to JsonPrimitive(revision)))
