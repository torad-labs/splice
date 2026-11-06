package splice.app.sources.observability

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfScope
import splice.core.perf.observation.PerfStamp
import splice.core.util.AsyncFileIo
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.head.perf.RequestEvidenceFiles
import splice.head.perf.RequestEvidenceFormat
import splice.head.perf.RequestEvidenceRetention
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.history.PerfEvidenceCursorRequest
import splice.usage.perf.history.PerfEvidenceQuery
import splice.usage.perf.history.PerfObservationPredicate
import splice.usage.perf.query.PerfQuery
import splice.usage.perf.query.PerfQueryService
import splice.usage.perf.query.PerfQuerySources
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class PerfEvidenceFileSourceTest {
    private val clock = WallClock { 1_000L }
    private val query = PerfEvidenceQuery(0L, 10_000L, "test")

    @Test
    fun `refusal events without a legacy row survive offline and duplicate observations are counted once`(
        @TempDir root: Path,
    ) {
        val dir = root.resolve("evidence")
        val writer = RequestEvidenceFiles(dir, clock = clock).forHead("test")
        val arrival = event("arrival", PerfEventKind.REQUEST_ARRIVED)
        assertTrue(writer.record(arrival))
        assertTrue(writer.record(arrival))
        assertTrue(writer.record(event("finished", PerfEventKind.REQUEST_FINISHED).copy(outcome = "refused")))
        assertTrue(AsyncFileIo.awaitDirectory(dir))
        val source = PerfEvidenceFileSource(dir, clock)
        source.read(query).use { read ->
            assertEquals(2, read.observations.size)
            assertEquals("refused", read.observations.last().outcome)
            assertTrue(read.coverage.issues.any { it.code == "evidence_prior_loss_unknown" })
        }
        source.page(PerfEvidenceCursorRequest(query)).use { page ->
            assertEquals(2, page.observations.size)
            assertEquals(2, page.observationIds.distinct().size)
            assertEquals("2", page.metadata["known_matching_events"].toString())
        }
    }

    @Test
    fun `a fixed event export survives restart and excludes later usage committed with the original timestamp`(
        @TempDir root: Path,
    ) {
        val dir = root.resolve("evidence")
        val writer = RequestEvidenceFiles(dir, clock = clock).forHead("test")
        assertTrue(writer.record(event("arrival", PerfEventKind.REQUEST_ARRIVED)))
        assertTrue(writer.record(event("finished", PerfEventKind.REQUEST_FINISHED)))
        assertTrue(AsyncFileIo.awaitDirectory(dir))
        val request = PerfEvidenceCursorRequest(query, pageSize = 1)
        val token = PerfEvidenceFileSource(dir, clock).page(request).use { page ->
            assertEquals(listOf("arrival"), page.observationIds)
            assertNotNull(page.nextCursor)
            page.nextCursor
        }
        assertTrue(writer.record(event("usage", PerfEventKind.USAGE_RECORDED)))
        assertTrue(AsyncFileIo.awaitDirectory(dir))
        PerfEvidenceFileSource(dir, clock).page(request.copy(cursor = token)).use { page ->
            assertEquals(listOf("finished"), page.observationIds)
            assertEquals(null, page.nextCursor)
        }
        PerfEvidenceFileSource(dir, clock).page(request.copy(pageSize = 10)).use { page ->
            assertEquals(listOf("arrival", "finished", "usage"), page.observationIds)
        }
    }

    @Test
    fun `head selection never parses unrelated malformed evidence`(@TempDir root: Path) {
        val dir = root.resolve("evidence")
        val files = RequestEvidenceFiles(dir, clock = clock)
        assertTrue(files.forHead("test").record(event("wanted", PerfEventKind.REQUEST_ARRIVED)))
        assertTrue(files.forHead("other").record(event("other", PerfEventKind.REQUEST_ARRIVED, "other")))
        assertTrue(AsyncFileIo.awaitDirectory(dir))
        val other = Files.newDirectoryStream(dir).use { paths ->
            paths.first { RequestEvidenceFormat.headOf(it.fileName.toString()) == "other" }
        }
        Files.writeString(other, "{broken\n", StandardOpenOption.APPEND)
        PerfEvidenceFileSource(dir, clock).read(query).use { read ->
            assertEquals(listOf("wanted"), read.observations.map { it.stamp.observationId })
            assertTrue(read.coverage.issues.none { it.code == "evidence_malformed" })
        }
    }

    @Test
    fun `event export continues past two thousand events with bounded pages and exact retained event count`(
        @TempDir root: Path,
    ) {
        val dir = root.resolve("evidence")
        val writer = RequestEvidenceFiles(dir, clock = clock).forHead("test")
        assertTrue(writer.record(event("0", PerfEventKind.REQUEST_ARRIVED)))
        assertTrue(AsyncFileIo.awaitDirectory(dir))
        val file = Files.newDirectoryStream(dir).use { paths ->
            paths.first { RequestEvidenceFormat.headOf(it.fileName.toString()) == "test" }
        }
        val lines = (1..2_204).joinToString("\n", postfix = "\n") {
            Json.encodeToString(event(it.toString(), PerfEventKind.REQUEST_FINISHED))
        }
        Files.writeString(file, lines, StandardOpenOption.APPEND)
        val source = PerfEvidenceFileSource(dir, clock)
        var request = PerfEvidenceCursorRequest(query, pageSize = 200)
        val ids = ArrayList<String>()
        do {
            val token = source.page(request).use { page ->
                assertEquals("2205", page.metadata["known_matching_events"].toString())
                ids += page.observationIds
                page.nextCursor
            }
            request = request.copy(cursor = token)
        } while (token != null)
        assertEquals(2_205, ids.size)
        assertEquals(2_205, ids.distinct().size)
    }

    @Test
    fun `conflicting observation payloads make event exports incomplete`(@TempDir root: Path) {
        val dir = root.resolve("evidence")
        val writer = RequestEvidenceFiles(dir, clock = clock).forHead("test")
        val finished = event("same-id", PerfEventKind.REQUEST_FINISHED).copy(outcome = "success")
        assertTrue(writer.record(finished))
        assertTrue(AsyncFileIo.awaitDirectory(dir))
        val file = Files.newDirectoryStream(dir).use { paths ->
            paths.first { RequestEvidenceFormat.headOf(it.fileName.toString()) == "test" }
        }
        Files.writeString(
            file,
            Json.encodeToString(finished.copy(outcome = "refused")) + "\n",
            StandardOpenOption.APPEND,
        )
        Files.writeString(
            dir.resolve(RequestEvidenceFormat.RETENTION_FILE),
            Json.encodeToString(RequestEvidenceRetention(lossAccountingComplete = true)),
        )
        val source = PerfEvidenceFileSource(dir, clock)
        source.read(query).use { read ->
            assertTrue(read.coverage.issues.any { it.code == "evidence_duplicate_conflict" })
        }
        source.page(PerfEvidenceCursorRequest(query)).use { page ->
            assertEquals(listOf("same-id"), page.observationIds)
            assertEquals("\"incomplete\"", page.metadata["status"].toString())
            assertEquals("1", page.metadata["known_matching_events"].toString())
            assertEquals(JsonNull, page.metadata["total_matching_events"])
            assertTrue(page.metadata["read_errors"].toString().contains("evidence_duplicate_conflict"))
        }
    }

    @Test
    fun `a filtered out observation cannot hide a conflicting matching duplicate`(@TempDir root: Path) {
        val dir = root.resolve("evidence")
        val writer = RequestEvidenceFiles(dir, clock = clock).forHead("test")
        val first = event("conflict", PerfEventKind.REQUEST_ARRIVED).copy(links = PerfLinks(sessionId = "a"))
        assertTrue(writer.record(first))
        assertTrue(AsyncFileIo.awaitDirectory(dir))
        val file = Files.newDirectoryStream(dir).use { paths ->
            paths.first { RequestEvidenceFormat.headOf(it.fileName.toString()) == "test" }
        }
        Files.writeString(
            file,
            Json.encodeToString(first.copy(links = PerfLinks(sessionId = "b"))) + "\n",
            StandardOpenOption.APPEND,
        )
        val request = PerfEvidenceCursorRequest(
            query,
            pageSize = 1,
            predicate = PerfObservationPredicate { it.links.sessionId == "b" },
            filterHash = "session-b",
        )
        PerfEvidenceFileSource(dir, clock).page(request).use {
            assertTrue(it.observations.isEmpty())
            assertEquals("incomplete", JsonScalars.str(it.metadata, "status"))
            assertEquals(JsonNull, it.metadata["total_matching_events"])
            assertTrue(it.metadata.toString().contains("evidence_duplicate_conflict"))
        }
    }

    @Test
    fun `event filters freeze matching counts before page one and remain stable across continuation`(
        @TempDir root: Path,
    ) {
        val dir = root.resolve("evidence")
        val writer = RequestEvidenceFiles(dir, clock = clock).forHead("test")
        listOf("excluded" to "a", "first" to "b", "second" to "b").forEach { (id, session) ->
            assertTrue(
                writer.record(event(id, PerfEventKind.REQUEST_ARRIVED).copy(links = PerfLinks(sessionId = session))),
            )
        }
        assertTrue(AsyncFileIo.awaitDirectory(dir))
        Files.writeString(
            dir.resolve(RequestEvidenceFormat.RETENTION_FILE),
            Json.encodeToString(RequestEvidenceRetention(lossAccountingComplete = true)),
        )
        val source = PerfEvidenceFileSource(dir, clock)
        val service = PerfQueryService(object : PerfQuerySources {
            override fun heads(): Set<String> = setOf("test")
            override fun rows(head: String): PerfRowsSource? = null
            override fun evidence(): PerfEvidenceFileSource = source
        })
        val selected = PerfQuery(0, 10_000, mapOf("head" to "test", "session" to "b"))
        val first = service.observations(selected, pageSize = 1)
        assertEquals(
            "first",
            JsonScalars.str(first.getValue("observations").jsonArray.single().jsonObject, "observation_id"),
        )
        assertEquals("2", JsonScalars.str(first.getValue("metadata").jsonObject, "total_matching_events"))
        val second = service.observations(selected, JsonScalars.str(first, "next_cursor"), 1)
        assertEquals(
            "second",
            JsonScalars.str(second.getValue("observations").jsonArray.single().jsonObject, "observation_id"),
        )
        assertEquals("true", JsonScalars.str(second, "export_complete"))
        PerfWorkBudget.release(first)
        PerfWorkBudget.release(second)
        val request = PerfEvidenceCursorRequest(
            query,
            pageSize = 1,
            predicate = PerfObservationPredicate { it.links.sessionId == "b" },
            filterHash = "session-b",
        )
        val token = source.page(request).use { it.nextCursor }
        source.page(request.copy(cursor = token, predicate = PerfObservationPredicate { false })).use {
            assertEquals(listOf("second"), it.observationIds, "continuation reads the frozen index")
        }
        source.page(request.copy(cursor = token, filterHash = "changed")).use {
            assertEquals("invalid", JsonScalars.str(it.metadata, "status"))
        }
    }

    private fun event(id: String, kind: PerfEventKind, head: String = "test"): PerfObservation = PerfObservation(
        PerfStamp(id, PerfIdentity("boot", "build", "1", "config"), 500L, 100L),
        PerfScope(head = head),
        kind,
        links = PerfLinks(requestId = "shared-request"),
    )
}
