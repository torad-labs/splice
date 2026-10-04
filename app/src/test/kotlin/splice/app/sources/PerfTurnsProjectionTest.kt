// NEW: differential real-route payload and repair controls for the projected turns source.
package splice.app.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.pool.HeadAccountPoolSource
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountView
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.perf.PerfArchiveName
import splice.core.perf.PerfKeys
import splice.core.util.JsonlSink
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.perf.PerfRoutes
import splice.usage.perf.PerfRowsSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Files
import java.nio.file.Path

class PerfTurnsProjectionTest {
    @Test
    fun `a second prefix edit falls back to a complete coherent window`(@TempDir dir: Path) {
        val file = dir.resolve("synthetic.jsonl")
        val tail = (2L..10_000L).joinToString("") { row(it) }
        Files.writeString(file, row(1) + tail)
        val source = PerfRowsFileSource(file)
        var edits = 0
        var calls = 0
        val full = source.projected(0) { projection ->
            calls++
            val first = projection.window.rows.first()
            if (edits < 2) {
                edits++
                Files.writeString(file, row(1, fields = tokens + (PerfKeys.IN_TOKENS to 1_000L + edits)) + tail)
            }
            val completed = projection.complete(listOf(first)).single()
            assertEquals(first.fields[PerfKeys.IN_TOKENS], completed.fields[PerfKeys.IN_TOKENS])
            completed
        }
        assertEquals(2, edits)
        assertEquals(3, calls, "two invalidated projections are followed by the full-window safety path")
        assertEquals(1_002L, full.fields[PerfKeys.IN_TOKENS])
    }

    @Test
    fun `deferred full rows never mix pre-edit aggregates with post-edit display facts`(@TempDir dir: Path) {
        val file = dir.resolve("synthetic.jsonl")
        val tail = (2L..10_000L).joinToString("") { row(it) }
        Files.writeString(file, row(1) + tail)
        val source = PerfRowsFileSource(file)
        var edited = false
        val full = source.projected(0) { projection ->
            val first = projection.window.rows.first()
            if (!edited) {
                edited = true
                Files.writeString(file, row(1, fields = tokens + (PerfKeys.IN_TOKENS to 2_000L)) + tail)
            }
            val completed = projection.complete(listOf(first)).single()
            assertEquals(
                first.fields[PerfKeys.IN_TOKENS],
                completed.fields[PerfKeys.IN_TOKENS],
                "aggregate facts and their full display row must come from the same coherent read",
            )
            completed
        }
        assertEquals(2_000L, full.fields[PerfKeys.IN_TOKENS])
    }

    private val tokens =
        mapOf(PerfKeys.IN_TOKENS to 1_000L, PerfKeys.OUT_TOKENS to 100L, PerfKeys.CACHED_TOKENS to 900L)

    @Test
    fun `projection keeps every pricing gap cause and arbitrary displayed numeric field`(@TempDir dir: Path) {
        val file = dir.resolve("synthetic.jsonl")
        Files.writeString(
            file,
            row(1) + row(2, fields = emptyMap()) + row(3, model = "free", account = "plan") +
                row(4, model = "free") + row(5, fields = tokens + ("synthetic_metric" to 777L)),
        )
        val source = PerfRowsFileSource(file)
        val head = head(source)
        val fallback = PerfRowsSource(source::window)
        val expected = render(head, fallback)
        assertEquals(expected.toString(), render(head, source).toString())
        val decoded = source.parsedLines
        assertEquals(expected.toString(), render(head, source).toString())
        assertEquals(decoded, source.parsedLines)
        val totals = expected.getValue("usage").jsonObject.getValue("totals").jsonObject
        listOf("uncounted", "plan", "undeclared").forEach { cause ->
            assertEquals("1", totals.getValue("unpriced_${cause}_requests").jsonPrimitive.content)
        }
        assertTrue(expected.toString().contains("\"synthetic_metric\":777"))
    }

    @Test
    fun `all selectors price presence and encounter order match the original full window`(@TempDir dir: Path) {
        val file = dir.resolve("synthetic.jsonl")
        val billing = tokens + mapOf(
            PerfKeys.CACHE_WRITE_TOKENS to 7L, PerfKeys.ABSORBED_ROUNDS to 2L,
            PerfKeys.ABSORBED_IN_TOKENS to 500_000L, PerfKeys.ABSORBED_CACHED_TOKENS to 1_000L,
            PerfKeys.ABSORBED_CACHE_WRITE_TOKENS to 2_000L, PerfKeys.ABSORBED_OUT_TOKENS to 50L,
        )
        Files.writeString(
            file,
            row(1) + row(4, fields = billing) + row(3, fields = tokens + (PerfKeys.LOCAL_STEP to 1L)) +
                row(4, compact = true) + "{\"ts\":5,\"model\":null,\"account\":null,\"outcome\":\"quota-refused\"}\n" +
                "malformed\n" + row(2),
        )
        val source = PerfRowsFileSource(file)
        val head = head(source)
        val original = PerfRowsSource(source::window)
        val selectors = listOf(
            emptyMap(), mapOf("until" to 4L), mapOf("outcome" to "failed"),
            mapOf("model" to "priced"), mapOf("account" to "key"), mapOf("session" to "synthetic"),
            mapOf("local" to true), mapOf("compact" to false), mapOf("compact" to true),
        )
        selectors.forEach { selector ->
            assertEquals(render(head, original, selector).toString(), render(head, source, selector).toString())
        }
        assertEquals(render(head, original, since = 3).toString(), render(head, source, since = 3).toString())
        assertEquals(render(head, original, since = 99).toString(), render(head, source, since = 99).toString())
        val latest = render(head, source).getValue("rows") as kotlinx.serialization.json.JsonArray
        assertEquals("5", latest.single().jsonObject.getValue("ts").jsonPrimitive.content)
        assertEquals(listOf(1L, 4L, 3L, 4L, 5L, 2L), source.window(0).rows.map { it.ts })
    }

    @Test
    fun `widening a prior day projection populates the week once and keeps it within its ceiling`(@TempDir dir: Path) {
        val history = SyntheticPerfHistory(dir)
        history.create()
        val source = PerfRowsFileSource(history.file)
        val head = head(source)
        val day = SCALE_SINCE + 6L * 86_400_000
        render(head, source, since = day)
        val first = render(head, source, since = SCALE_SINCE).toString()
        val shifted = render(head, PerfRowsSource(source::window), since = SCALE_SINCE + 1).toString()
        val decoded = source.parsedLines
        assertEquals(shifted, render(head, source, since = SCALE_SINCE + 1).toString())
        assertEquals(first, render(head, source, since = SCALE_SINCE).toString())
        assertEquals(decoded, source.parsedLines)
        assertTrue(source.projectedBytes <= PERF_CACHE_BYTES)
    }

    @Test
    fun `product appends read only their suffix while same-growth repairs recover the changed prefix`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("synthetic.jsonl")
        Files.writeString(file, (1L..100L).joinToString("") { row(it) })
        val source = PerfRowsFileSource(file)
        val head = head(source)
        render(head, source)
        val before = source.parsedLines
        val addition = row(101)
        JsonlSink.appendLine(file, addition.trimEnd(), maxBytes = Long.MAX_VALUE)
        val profiler = PerfHistoryProfile()
        val appended = profiler.diskPhase("product_append", dir.resolve("append.jfr"), setOf(file)) {
            render(head, source)
        }
        assertEquals(before + 1, source.parsedLines)
        assertTrue(profiler.diskBytes <= addition.toByteArray().size, "a proven append reads only its new bytes")
        assertEquals("101", appended.getValue("count").jsonPrimitive.content)
        val repaired = (1L..100L).joinToString("") { row(it + 1_000) } + row(101)
        val key = Files.getAttribute(file, "fileKey")
        val modified = Files.getLastModifiedTime(file)
        Files.writeString(file, repaired)
        Files.setLastModifiedTime(file, modified)
        assertEquals(key, Files.getAttribute(file, "fileKey"), "the control repairs the same inode")
        // No reader observes the edit before the product appends. A last-writer receipt cannot hide the repair.
        JsonlSink.appendLine(file, row(102).trimEnd(), maxBytes = Long.MAX_VALUE)
        val full = PerfRowsSource(source::window)
        assertEquals(
            render(head, full).toString(),
            render(head, source).toString(),
            "growth is not append proof across a rewritten prefix",
        )
    }

    @Test
    fun `rotation and repaired unterminated tails retain original full display rows`(@TempDir dir: Path) {
        val file = dir.resolve("synthetic.jsonl")
        val rotated = file.resolveSibling("${file.fileName}.1")
        val archive = Files.createDirectory(dir.resolve("archive"))
        Files.writeString(rotated, row(1))
        Files.writeString(file, row(2).trimEnd())
        val source = PerfRowsFileSource(file, archive)
        val head = head(source)
        val original = PerfRowsSource(source::window)
        assertEquals(render(head, original).toString(), render(head, source).toString())
        JsonlSink.appendLine(file, row(3).trimEnd(), maxBytes = Long.MAX_VALUE)
        assertEquals(render(head, original).toString(), render(head, source).toString())
        Files.move(rotated, archive.resolve(PerfArchiveName(file.fileName.toString()).of(10_000)))
        Files.move(file, rotated)
        Files.writeString(file, row(4))
        assertEquals(render(head, original).toString(), render(head, source).toString())
    }

    private fun row(
        ts: Long,
        model: String = "priced",
        account: String = "key",
        fields: Map<String, Long> = tokens,
        compact: Boolean = false,
    ): String = buildJsonObject {
        put("ts", ts)
        put("outcome", "ok")
        put("model", model)
        put("account", account)
        put("session", "synthetic")
        put("session_id", "synthetic-full")
        put("turn", "synthetic-turn-$ts")
        put("response_message_id", "synthetic-response-$ts")
        put("compact", compact)
        fields.forEach { (key, value) -> put(key, value) }
    }.toString() + "\n"

    private fun head(source: PerfRowsSource): UsageHead = UsageHead(
        "synthetic",
        "synthetic",
        HeadUsageSource { UsageView(0, 0, null) },
        80,
        0,
        perfRows = source,
        catalog = ModelCatalog(
            discoveryPrefix = "synthetic--",
            models = listOf(
                ModelEntry("priced", contextWindow = 100_000, rates = ModelRates(1.0, 0.1, 4.0)),
                ModelEntry("free", contextWindow = 100_000),
            ),
            defaultContextWindow = 100_000,
        ),
        accountPool = HeadAccountPoolSource {
            HeadAccountPoolView(
                "plan",
                listOf(
                    HeadAccountView("plan", true, true, true, "pro", null, null, null, null),
                    HeadAccountView("key", false, false, true, null, null, null, null, null),
                ),
                null,
            )
        },
    )

    private fun render(
        head: UsageHead,
        source: PerfRowsSource,
        selectors: Map<String, Any> = emptyMap(),
        since: Long = 0L,
    ): JsonObject {
        val filterType = Class.forName("splice.usage.perf.TurnsFilter")
        val filter = filterType.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
            .newInstance(
                selectors["until"],
                selectors["outcome"],
                selectors["model"],
                selectors["account"],
                selectors["session"],
                null,
                selectors["local"] ?: false,
                selectors["compact"],
            )
        val windowType = Class.forName("splice.usage.perf.AskedWindow")
        val asked = windowType.getDeclaredConstructor(
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            filterType,
            java.time.ZoneId::class.java,
        ).apply { isAccessible = true }.newInstance(since, 1, filter, java.time.ZoneId.of("America/Chicago"))
        val routes = PerfRoutes(UsageHeadLookup { listOf(head) }, WallClock { 200_000 })
        return PerfRoutes::class.java.getDeclaredMethod(
            "turnsFor",
            UsageHead::class.java,
            PerfRowsSource::class.java,
            windowType,
        ).apply { isAccessible = true }.invoke(routes, head, source, asked) as JsonObject
    }
}
