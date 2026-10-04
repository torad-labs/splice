// NEW: V4-454 — historical probes leave no economics work; correction never edits history.
package splice.app.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TurnPrice
import splice.core.turn.AbsorbedRounds
import splice.core.util.WallClock
import splice.head.usage.EconomicsStore
import splice.head.usage.TurnEconomics
import splice.usage.UsageHead
import splice.usage.UsageHeads
import splice.usage.economics.EconomicsPayloads
import splice.usage.economics.EconomicsRead
import splice.usage.economics.EconomicsRow
import splice.usage.economics.HeadEconomicsSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Files
import java.nio.file.Path

private const val PROBE_HOUR = 3_600_000L
private const val SYNTHETIC_PROBE_ROW =
    """{"ts":3600123,"model":"","outcome":"error:upstream-failed","req_bytes":30,"upstream_req_bytes":15,"tools_eager":2,"tools_deferred":1}"""

private const val MODELED_WORK_ROW =
    """{"ts":3600123,"model":"synthetic","outcome":"ok","req_bytes":100,"upstream_req_bytes":50,"tools_eager":3,"tools_deferred":2,"in_tokens":5,"cached_tokens":2,"cache_write_tokens":1,"out_tokens":10}"""
private const val ABSORBING_WORK_ROW =
    """{"ts":3600123,"model":"synthetic","outcome":"ok","req_bytes":100,"upstream_req_bytes":50,"tools_eager":3,"tools_deferred":2,"in_tokens":5,"cached_tokens":2,"cache_write_tokens":1,"out_tokens":10,"absorbed_rounds":1,"absorbed_in_tokens":4,"absorbed_cached_tokens":1,"absorbed_cache_write_tokens":1,"absorbed_out_tokens":3}"""
private const val LOCAL_WORK_ROW =
    """{"ts":3600123,"model":"synthetic","outcome":"ok","local_step":1,"req_bytes":20,"upstream_req_bytes":10,"tools_eager":1,"tools_deferred":0,"in_tokens":7,"cached_tokens":3,"cache_write_tokens":2,"out_tokens":14}"""

class ProbeEconomicsTest {
    @Test
    fun `probes are subtracted once while modeled and local work survive polls and reload`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            listOf(SYNTHETIC_PROBE_ROW, MODELED_WORK_ROW, LOCAL_WORK_ROW).joinToString("\n") + "\n",
        )
        val store = store(dir)
        store.record(turn("", 0, 30, 15, 2L to 1L))
        recordWork(store)
        store.flushNow()
        val original = Files.readString(dir.resolve("economics.json"))
        val control = store(dir.resolve("control"))
        recordWork(control)
        val source = EconomicsStoreSource(store, PerfRowsFileSource(file))
        val expected = EconomicsStoreSource(control).rows()
        assertEquals(expected, source.rows())
        assertEquals(expected, source.rows())
        assertEquals(expected, EconomicsStoreSource(store(dir), PerfRowsFileSource(file)).rows())
        assertEquals(original, Files.readString(dir.resolve("economics.json")))
        // A healthy second poll must not re-scan history: the frozen deductions still apply.
        Files.delete(file)
        assertEquals(expected, source.rows())
    }

    // The rollup meters a turn's absorbed rounds beside its final round, so the perf evidence must sum
    // them the same way, or every hour with a hidden code-mode round fails to reconcile.
    @Test
    fun `a turn's absorbed rounds reconcile between perf evidence and the rollup`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(file, listOf(SYNTHETIC_PROBE_ROW, ABSORBING_WORK_ROW).joinToString("\n") + "\n")
        val script = AbsorbedRounds(rounds = 1, inputTokens = 4, cachedTokens = 1, cacheWriteTokens = 1)
            .copy(outputTokens = 3)
        val store = store(dir)
        store.record(turn("", 0, 30, 15, 2L to 1L))
        store.record(turn("synthetic", 5, 100, 50, 3L to 2L, script))
        val control = store(dir.resolve("control"))
        control.record(turn("synthetic", 5, 100, 50, 3L to 2L, script))

        val buckets = EconomicsStoreSource(store, PerfRowsFileSource(file)).rows()
        assertEquals(EconomicsStoreSource(control).rows(), buckets)
        assertEquals(9L, buckets.single().inTokens, "the final round's 5 and the absorbed round's 4")
    }

    @Test
    fun `a probe-only bucket disappears`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(file, SYNTHETIC_PROBE_ROW + "\n")
        val store = store(dir)
        store.record(turn("", 0, 30, 15, 2L to 1L))
        assertEquals(emptyList<Any>(), EconomicsStoreSource(store, PerfRowsFileSource(file)).rows())
    }

    @Test
    fun `unreadable perf evidence cannot silently return clean economics`(@TempDir dir: Path) {
        val file = Files.createDirectory(dir.resolve("head-perf.jsonl"))
        val store = store(dir)
        recordWork(store)
        val source = EconomicsStoreSource(store, PerfRowsFileSource(file))
        assertEquals(ProbeGap.UNREADABLE.sentence, source.gap())
    }

    @Test
    fun `unpriced legacy hours stay unknown after probe correction`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(file, SYNTHETIC_PROBE_ROW + "\n" + MODELED_WORK_ROW + "\n")
        Files.writeString(
            dir.resolve("economics.json"),
            """[{"hour":3600000,"turns":2,"req_bytes":130,"upstream_req_bytes":65,"tools_eager":5,"tools_deferred":3,"deferral_turns":2,"unpriced_turns":1,"in_tokens":5,"cached_tokens":2,"cache_write_tokens":1,"out_tokens":10}]""",
        )
        val row = EconomicsStoreSource(store(dir), PerfRowsFileSource(file)).rows().single()
        assertEquals(1L, row.turns)
        assertEquals(5L, row.inTokens)
        assertEquals(10L, row.outTokens)
        assertEquals(2L, row.cachedTokens)
        assertEquals(1L, row.cacheWriteTokens)
        assertNull(row.costUsd)
    }

    @Test
    fun `a torn probe cannot freeze an empty correction and its repaired evidence is retried`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        val store = store(dir)
        store.record(turn("", 0, 30, 15, 2L to 1L))
        Files.writeString(file, SYNTHETIC_PROBE_ROW.dropLast(1) + "\n")
        val source = EconomicsStoreSource(store, PerfRowsFileSource(file))
        assertEquals(ProbeGap.UNREADABLE.sentence, source.gap())
        Files.writeString(file, SYNTHETIC_PROBE_ROW + "\n")
        assertEquals(emptyList<Any>(), source.rows())
    }

    @Test
    fun `rotated-away probe evidence cannot leave contaminated usage looking clean`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        val store = store(dir)
        store.record(turn("", 0, 30, 15, 2L to 1L))
        recordWork(store)
        Files.writeString(file, MODELED_WORK_ROW + "\n" + LOCAL_WORK_ROW + "\n")
        assertEquals(ProbeGap.UNRECONCILED.sentence, EconomicsStoreSource(store, PerfRowsFileSource(file)).gap())
    }

    @Test
    fun `later live work cannot invalidate an already reconciled legacy hour`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            SYNTHETIC_PROBE_ROW + "\n" + MODELED_WORK_ROW.replace("3600123", "7200123") + "\n",
        )
        val store = store(dir)
        store.record(turn("", 0, 30, 15, 2L to 1L))
        assertEquals(emptyList<Any>(), EconomicsStoreSource(store, PerfRowsFileSource(file)).rows())
    }

    @Test
    fun `a probe response id authored by splice is not command provenance`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(file, SYNTHETIC_PROBE_ROW.dropLast(1) + ""","response_message_id":"synthetic-id"}""" + "\n")
        val store = store(dir)
        store.record(turn("", 0, 30, 15, 2L to 1L))
        assertEquals(emptyList<Any>(), EconomicsStoreSource(store, PerfRowsFileSource(file)).rows())
    }

    @Test
    fun `perf-only local refusals do not invent a missing economics contribution`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            listOf(
                SYNTHETIC_PROBE_ROW,
                MODELED_WORK_ROW,
                LOCAL_WORK_ROW,
                """{"ts":3600123,"model":"synthetic","outcome":"error:rate-limited","attempts":0,"req_bytes":100}""",
            ).joinToString("\n") + "\n",
        )
        val store = store(dir)
        store.record(turn("", 0, 30, 15, 2L to 1L))
        recordWork(store)
        val control = store(dir.resolve("control"))
        recordWork(control)
        assertEquals(
            EconomicsStoreSource(control).rows(),
            EconomicsStoreSource(store, PerfRowsFileSource(file)).rows(),
        )
    }

    @Test
    fun `a probe crossing an hour cannot erase the preceding genuine bucket`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        val before = PROBE_HOUR - 1
        Files.writeString(
            file,
            MODELED_WORK_ROW.replace("3600123", before.toString()) + "\n" +
                SYNTHETIC_PROBE_ROW.replace("3600123", before.toString()) + "\n",
        )
        var now = before
        val store = store(dir, WallClock { now })
        store.record(turn("synthetic", 5, 100, 50, 3L to 2L))
        now = PROBE_HOUR
        store.record(turn("", 0, 30, 15, 2L to 1L))
        val original = store.read()
        assertEquals(ProbeGap.UNRECONCILED.sentence, EconomicsStoreSource(store, PerfRowsFileSource(file)).gap())
        assertEquals(original, store.read(), "uncertainty never mutates the genuine bucket")
    }

    // splice-lead, Oct 4: GET /api/economics failed on every poll from 5:18 AM CT because one head's
    // evidence threw. A head that cannot reconcile now answers unavailable with its reason, alone.
    @Test
    fun `one head whose evidence does not reconcile answers unavailable while the others still answer`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("head-perf.jsonl")
        // The probe's own row rotated away, so its rollup cannot be cleaned.
        Files.writeString(file, MODELED_WORK_ROW + "\n" + LOCAL_WORK_ROW + "\n")
        val contaminated = store(dir.resolve("contaminated"))
        contaminated.record(turn("", 0, 30, 15, 2L to 1L))
        recordWork(contaminated)
        val healthy = store(dir.resolve("healthy"))
        recordWork(healthy)
        val heads = UsageHeads {
            listOf(
                head("claudex", EconomicsStoreSource(contaminated, PerfRowsFileSource(file))),
                head("grok", EconomicsStoreSource(healthy)),
            )
        }

        val payload = Json.parseToJsonElement(EconomicsPayloads(heads).economicsJson()).jsonObject
        val (hidden, answering) = payload.getValue("heads").jsonArray.map { it.jsonObject }

        assertEquals(ProbeGap.UNRECONCILED.sentence, hidden.getValue("unavailable").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), hidden.getValue("buckets"), "no hour of the head that cannot be cleaned")
        assertNull(answering["unavailable"], "the other head is not marked")
        val hour = answering.getValue("buckets").jsonArray.single().jsonObject
        val held = EconomicsStoreSource(healthy).rows().single()
        assertEquals(held.inTokens, hour.getValue("in_tokens").jsonPrimitive.long, "the other head's hour, whole")
    }

    private fun EconomicsStoreSource.rows(): List<EconomicsRow> =
        assertInstanceOf(EconomicsRead.Rows::class.java, read()).rows

    private fun EconomicsStoreSource.gap(): String =
        assertInstanceOf(EconomicsRead.Unavailable::class.java, read()).reason

    private fun head(key: String, economics: HeadEconomicsSource) =
        UsageHead(key, key, HeadUsageSource { UsageView(0, 0, null) }, 80, 0, economics = economics)

    private fun recordWork(store: EconomicsStore) {
        store.record(turn("synthetic", 5, 100, 50, 3L to 2L))
        store.record(turn("synthetic", 7, 20, 10, 1L to 0L).copy(localStep = true))
    }

    private fun store(dir: Path, clock: WallClock = WallClock { PROBE_HOUR + 123 }): EconomicsStore = EconomicsStore(
        dir.resolve("economics.json"),
        TurnPrice(
            ModelCatalog(
                discoveryPrefix = "synthetic--",
                models = listOf(
                    ModelEntry("synthetic", contextWindow = 1000, rates = ModelRates(3.0, 0.3, 15.0, 6.0)),
                ),
                defaultContextWindow = 1000,
            ),
        ),
        clock,
        log = { },
    )

    private fun turn(
        model: String,
        input: Long,
        request: Long,
        upstream: Long,
        tools: Pair<Long, Long>,
        absorbed: AbsorbedRounds = AbsorbedRounds(),
    ) = TurnEconomics(
        model,
        input,
        input / 2,
        input / 3,
        input * 2,
        request,
        upstream,
        tools.first,
        tools.second,
        rateLimited = input > 0,
        absorbed = absorbed,
    )
}
