package splice.app.sources.observability

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.sources.PerfRowsFileSource
import splice.app.sources.observability.cursor.PERF_CURSOR_TTL_MS
import splice.app.sources.observability.cursor.PerfCommittedCursors
import splice.app.sources.observability.cursor.PerfCursorPruning
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.usage.perf.history.PerfCursorQuery
import splice.usage.perf.history.PerfCursorRequest
import splice.usage.perf.history.PerfRowPredicate
import splice.usage.perf.query.PerfQuery
import splice.usage.perf.query.PerfQueryService
import splice.usage.perf.query.PerfQuerySources
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PerfCursorCapacityTest {
    @TempDir
    lateinit var root: Path

    @AfterEach
    fun releaseCursorSweeps() {
        PerfCursorPruning.closeRoot(root)
    }

    @Test
    fun `the sixty fifth first page refuses while existing pages and the same measured cohort remain available`() {
        StreamingEvidenceFixture(root).use { fixture ->
            fixture.seed(110)
            val file = rows(root)
            val source = PerfRowsFileSource(file)
            val request = request()
            val token = fill(source, request, 64)
            source.page(request).use(::assertCapacity)
            assertContinuation(file, request, token)
            assertKnown(fixture)

            // Reproduce an old writer's 130-file inventory without removing or expiring any valid token.
            val directory = file.resolveSibling("${file.fileName}-cursors")
            val original = token.substringBefore(':')
            val extra = UUID.randomUUID().toString()
            for (suffix in listOf("idx", "json")) {
                Files.copy(directory.resolve("$original.$suffix"), directory.resolve("$extra.$suffix"))
            }
            source.page(request).use(::assertCapacity)
            assertContinuation(file, request, token)
            assertKnown(fixture)
        }
    }

    @Test
    fun `competing source owners reserve the last pair under the shared file lock`() {
        val file = rows(root)
        val request = request()
        val token = fill(PerfRowsFileSource(file), request, 63)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = request.copy(
            predicate = PerfRowPredicate {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                true
            },
        )
        Executors.newSingleThreadExecutor().use { worker ->
            val creating = worker.submit<String?> {
                PerfRowsFileSource(file).page(blocking).use { JsonScalars.str(it.metadata, "status") }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                PerfRowsFileSource(file).page(request).use { contender ->
                    assertEquals("busy", JsonScalars.str(contender.metadata, "status"))
                    assertTrue(contender.metadata.toString().contains("cursor_busy"))
                }
            } finally {
                release.countDown()
            }
            assertEquals("complete", creating.get(5, TimeUnit.SECONDS))
        }
        PerfRowsFileSource(file).page(request).use(::assertCapacity)
        assertContinuation(file, request, token)
    }

    @Test
    fun `oversized expired inventories recover through bounded cleanup without evicting a live continuation`() {
        val file = rows(root)
        val now = System.currentTimeMillis()
        val source = PerfCommittedCursors(file, null, PerfSourceCosts(), WallClock { now })
        val request = request()
        val token = source.page(request).use { requireNotNull(it.nextCursor) }
        val directory = file.resolveSibling("${file.fileName}-cursors")
        repeat(128) { index ->
            val expired = directory.resolve("expired-$index.idx")
            Files.writeString(expired, "expired")
            Files.setLastModifiedTime(expired, FileTime.fromMillis(now - PERF_CURSOR_TTL_MS - 1))
        }
        source.page(request).use(::assertCapacity)
        source.page(request).use(::assertCapacity)
        source.page(request).use { recovered ->
            assertEquals("complete", JsonScalars.str(recovered.metadata, "status"))
            assertEquals(100, recovered.rows.size)
            assertNotNull(recovered.nextCursor)
        }
        assertContinuation(file, request, token)
    }

    @Test
    fun `expired tail beyond a live prefix is reached across source instances before fresh admission`() {
        val file = rows(root)
        val request = request()
        fill(PerfRowsFileSource(file), request, 64)
        val directory = file.resolveSibling("${file.fileName}-cursors")
        repeat(2) { Files.writeString(directory.resolve("overflow-$it.idx"), "old") }
        // Pin the actual iteration order: exactly the entries beyond the first live128 are expired.
        val ordered = Files.newDirectoryStream(directory).use { it.toList() }
        val prefix = ordered.take(128).toSet()
        val retainedId = prefix.first { path ->
            path.fileName.toString().endsWith(".json") &&
                directory.resolve(path.fileName.toString().removeSuffix(".json") + ".idx") in prefix
        }.fileName.toString().removeSuffix(".json")
        val now = System.currentTimeMillis()
        ordered.drop(128).forEach { Files.setLastModifiedTime(it, FileTime.fromMillis(now - PERF_CURSOR_TTL_MS - 1)) }
        val other = root.resolve("other.jsonl")
        Files.copy(file, other)
        repeat(2) { PerfRowsFileSource(other).page(request).use(::assertCapacity) }
        PerfRowsFileSource(other).page(request).use { recovered ->
            assertEquals("complete", JsonScalars.str(recovered.metadata, "status"))
            assertEquals(100, recovered.rows.size)
            assertNotNull(recovered.nextCursor)
        }
        assertContinuation(file, request, "$retainedId:100")
    }

    @Test
    fun `row query preserves fresh admission refusal and an actually expired continuation cause`() {
        val query = PerfQuery(1_000, 12_000, source = "legacy")
        val saturatedFile = rows(root)
        val saturatedSource = PerfRowsFileSource(saturatedFile)
        fill(saturatedSource, request(), 64)
        val saturatedService = queryService(saturatedSource)
        try {
            val saturatedReply = saturatedService.rows(query, pageSize = 1)
            try {
                assertEquals("cursor_inventory_limit", firstIssue(saturatedReply))
            } finally {
                PerfWorkBudget.release(saturatedReply)
            }
        } finally {
            saturatedService.close()
        }

        val expiredFile = root.resolve("expired.jsonl").also { file -> Files.copy(saturatedFile, file) }
        val expiredSource = PerfRowsFileSource(expiredFile)
        val service = queryService(expiredSource)
        try {
            val first = service.rows(query, pageSize = 1)
            val cursor: String
            val sourceSnapshot: String
            try {
                cursor = requireNotNull(JsonScalars.str(first, "next_cursor"))
                sourceSnapshot = requireNotNull(JsonScalars.str(first.getValue("snapshot_id").jsonObject, "test"))
            } finally {
                PerfWorkBudget.release(first)
            }
            val cursorDirectory = expiredFile.resolveSibling("${expiredFile.fileName}-cursors")
            Files.delete(cursorDirectory.resolve("$sourceSnapshot.json"))

            val expired = service.rows(query, cursor, pageSize = 1)
            try {
                assertEquals("cursor_snapshot_expired", firstIssue(expired))
            } finally {
                PerfWorkBudget.release(expired)
            }
        } finally {
            service.close()
        }
    }

    private fun queryService(source: PerfRowsFileSource): PerfQueryService =
        PerfQueryService(
            object : PerfQuerySources {
                override fun heads(): Set<String> = setOf("test")
                override fun rows(head: String) = source.takeIf { head == "test" }
            },
        )

    private fun firstIssue(reply: kotlinx.serialization.json.JsonObject): String? =
        reply["coverage"]?.jsonObject?.get("issues")?.jsonArray?.firstOrNull()?.jsonObject?.let {
            JsonScalars.str(it, "code")
        }

    private fun rows(root: Path): Path = root.resolve("test.jsonl").also { path ->
        Files.writeString(
            path,
            (0 until 110).joinToString("") { index ->
                "{\"ts\":${1_000 + index * 100},\"model\":\"test\",\"outcome\":\"ok\"}\n"
            },
        )
    }

    private fun request(): PerfCursorRequest = PerfCursorRequest(
        PerfCursorQuery(1_000, 12_000, "all"),
        PerfRowPredicate { true },
        pageSize = 100,
    )

    private fun fill(source: PerfRowsFileSource, request: PerfCursorRequest, count: Int): String {
        var token: String? = null
        repeat(count) {
            source.page(request).use { page ->
                assertEquals("complete", JsonScalars.str(page.metadata, "status"))
                assertEquals(100, page.rows.size)
                token = requireNotNull(page.nextCursor)
            }
        }
        return requireNotNull(token)
    }

    private fun assertCapacity(page: splice.usage.perf.history.PerfCursorPage) {
        assertEquals("incomplete", JsonScalars.str(page.metadata, "status"))
        assertTrue(page.metadata.toString().contains("cursor_inventory_limit"))
        assertTrue(page.rows.isEmpty())
        assertNull(page.nextCursor)
    }

    private fun assertContinuation(file: Path, request: PerfCursorRequest, token: String) {
        PerfRowsFileSource(file).page(request.copy(cursor = token)).use { page ->
            assertEquals("complete", JsonScalars.str(page.metadata, "status"))
            assertEquals(10, page.rows.size)
            assertNull(page.nextCursor)
        }
    }

    private fun assertKnown(fixture: StreamingEvidenceFixture) {
        val result = fixture.service().use { it.summary(fixture.query()) }
        try {
            assertEquals("110", JsonScalars.str(result.getValue("counts").jsonObject, "arrivals"))
            assertEquals("110", JsonScalars.str(result.getValue("counts").jsonObject, "completions"))
            assertEquals("ready", JsonScalars.str(result.getValue("comparison").jsonObject, "status"))
            assertEquals("true", JsonScalars.str(result.getValue("coverage").jsonObject, "complete"))
        } finally {
            PerfWorkBudget.release(result)
        }
        val series = fixture.service().use { it.series(fixture.query()) }
        try {
            assertEquals("110", JsonScalars.str(series.getValue("counts").jsonObject, "arrivals"))
            assertEquals("true", JsonScalars.str(series.getValue("coverage").jsonObject, "complete"))
        } finally {
            PerfWorkBudget.release(series)
        }
    }
}
