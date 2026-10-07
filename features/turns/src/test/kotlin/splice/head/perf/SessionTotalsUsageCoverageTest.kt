package splice.head.perf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TurnBill
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.core.turn.AbsorbedRounds
import splice.core.turn.Usage
import splice.core.turn.UsageField
import splice.core.turn.UsageHistory
import splice.core.turn.noRequestUsage
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

class SessionTotalsUsageCoverageTest {
    @Test
    fun `a legacy persisted model defaults only the new coverage count`(@TempDir tmp: Path) {
        val file = tmp.resolve("synthetic-old.json")
        Files.writeString(
            file,
            """{"clean":true,"since":1,"sessions":[{"session":"feed0000","from":1,"last":2,"models":""" +
                """{"synthetic":{"turns":1,"in_tokens":100,"cached_tokens":20,"cache_write_tokens":0,""" +
                """"out_tokens":7,"cost_usd":0.5,"unpriced_turns":0}}}]}""",
        )
        val totals = SessionTotals(file, TurnPrice(null), WallClock { 3L }, LogSink {})
        val kept = checkNotNull(totals.totalFor("feed0000-synthetic")).models.getValue("synthetic")
        assertEquals(0L, kept.unreportedUsageTurns)
        assertEquals(100L, kept.inTokens)
        assertEquals(0.5, kept.usd)
        totals.flushNow()
    }

    @Test
    fun `known absorbed input survives a final request whose input is unreported`(@TempDir tmp: Path) {
        val file = tmp.resolve("synthetic-absorbed.json")
        val totals = SessionTotals(file, TurnPrice(null), WallClock { 1L }, LogSink {})
        val usage = Usage(
            outputTokens = 7,
            history = UsageHistory(absorbed = AbsorbedRounds(1, 100, 20, 10, 7)),
            reported = setOf(UsageField.OUTPUT),
        )
        totals.add("feed0000", "synthetic", TurnBill.counters(usage), 2L)
        val model = checkNotNull(totals.totalFor("feed0000-synthetic")).models.getValue("synthetic")
        assertEquals(100L, model.inTokens)
        assertEquals(20L, model.cachedTokens)
        assertEquals(10L, model.cacheWriteTokens)
        assertEquals(7L, model.outTokens)
        assertEquals(1L, model.unreportedUsageTurns)
        totals.flushNow()
    }

    private fun priced(file: Path) = SessionTotals(
        file,
        TurnPrice(
            ModelCatalog(
                discoveryPrefix = "synthetic--",
                models = listOf(ModelEntry("synthetic", contextWindow = 100_000, rates = ModelRates(2.0, 0.2, 10.0))),
                defaultContextWindow = 100_000,
            ),
        ),
        WallClock { 1L },
        LogSink {},
    )

    @Test
    fun `a local refusal does not poison a session's exact zero spend`(@TempDir tmp: Path) {
        val totals = priced(tmp.resolve("refused-session.json"))
        val zero = TurnBill.counters(Usage()) + (PerfKeys.TRANSPORT_ATTEMPT_STARTS to 1L)
        totals.add("feed0000", "synthetic", zero, 2L)
        totals.add("feed0000", "synthetic", TurnBill.counters(noRequestUsage), 3L)
        val model = checkNotNull(totals.totalFor("feed0000-synthetic")).models.getValue("synthetic")
        assertEquals(0L, model.unreportedUsageTurns)
        assertEquals(0L, model.unpricedTurns)
        assertEquals(0.0, model.usd)
        totals.flushNow()
    }

    @Test
    fun `a no-request ending keeps earlier measured spend exact in session totals`(@TempDir tmp: Path) {
        val totals = priced(tmp.resolve("local-ending-session.json"))
        val usage = noRequestUsage.copy(
            outputTokens = 7,
            history = noRequestUsage.history.copy(absorbed = AbsorbedRounds(1, 100, 20, 0, 7)),
            reported = setOf(UsageField.OUTPUT),
        )
        totals.add("feed0000", "synthetic", TurnBill.counters(usage), 2L)
        val model = checkNotNull(totals.totalFor("feed0000-synthetic")).models.getValue("synthetic")
        assertEquals(0L, model.unreportedUsageTurns)
        assertEquals(0L, model.unpricedTurns)
        assertEquals(0.000234, model.usd, 1e-12)
        totals.flushNow()
    }

    @Test
    fun `persisted totals mark a posted turn whose usage never arrived`(@TempDir tmp: Path) {
        val file = tmp.resolve("synthetic-totals.json")
        val totals = SessionTotals(file, TurnPrice(null), WallClock { 1L }, LogSink {})
        totals.add("feed0000", "synthetic", mapOf(PerfKeys.TRANSPORT_ATTEMPT_STARTS to 1L), 2L)
        totals.flushNow()
        val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
        val model = root.getValue("sessions").jsonArray.single().jsonObject
            .getValue("models").jsonObject.getValue("synthetic").jsonObject
        assertEquals("1", model["unreported_usage_turns"]?.jsonPrimitive?.content)
        assertEquals("0", model.getValue("in_tokens").jsonPrimitive.content)
    }
}
