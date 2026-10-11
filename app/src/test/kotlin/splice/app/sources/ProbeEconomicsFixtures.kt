// Fixtures shared by the economics reconciliation tests.
package splice.app.sources

import org.junit.jupiter.api.Assertions.assertInstanceOf
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TurnPrice
import splice.core.turn.UsageHistory
import splice.core.util.WallClock
import splice.head.usage.AccountOnPlan
import splice.head.usage.EconomicsStore
import splice.head.usage.NoPlans
import splice.head.usage.TurnBytes
import splice.head.usage.TurnEconomics
import splice.head.usage.TurnTokens
import splice.head.usage.TurnTools
import splice.usage.UsageHead
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadWarn
import splice.usage.economics.EconomicsRead
import splice.usage.economics.EconomicsRow
import splice.usage.economics.HeadEconomicsSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Path

internal const val PROBE_HOUR = 3_600_000L
internal const val SYNTHETIC_PROBE_ROW =
    """{"ts":3600123,"model":"","outcome":"error:upstream-failed","req_bytes":30,""" +
        """"upstream_req_bytes":15,"tools_eager":2,"tools_deferred":1}"""

internal const val MODELED_WORK_ROW =
    """{"ts":3600123,"model":"synthetic","outcome":"ok","req_bytes":100,"upstream_req_bytes":50,"tools_eager":3,""" +
        """"tools_deferred":2,"in_tokens":5,"cached_tokens":2,"cache_write_tokens":1,"out_tokens":10}"""
internal const val ABSORBING_WORK_ROW =
    """{"ts":3600123,"model":"synthetic","outcome":"ok","req_bytes":100,"upstream_req_bytes":50,"tools_eager":3,""" +
        """"tools_deferred":2,"in_tokens":5,"cached_tokens":2,"cache_write_tokens":1,"out_tokens":10,""" +
        """"absorbed_rounds":1,"absorbed_in_tokens":4,"absorbed_cached_tokens":1,""" +
        """"absorbed_cache_write_tokens":1,"absorbed_out_tokens":3}"""
internal const val LOCAL_WORK_ROW =
    """{"ts":3600123,"model":"synthetic","outcome":"ok","local_step":1,"req_bytes":20,"upstream_req_bytes":10,""" +
        """"tools_eager":1,"tools_deferred":0,"in_tokens":7,"cached_tokens":3,"cache_write_tokens":2,"out_tokens":14}"""

internal fun shown(store: EconomicsStore, file: Path): EconomicsRead.Rows =
    assertInstanceOf(EconomicsRead.Rows::class.java, EconomicsStoreSource(store, PerfRowsFileSource(file)).read())

internal fun atHour(ts: Long, vararg rows: String): String =
    rows.joinToString("") { it.replace("3600123", ts.toString()) + "\n" }

internal fun recordWork(store: EconomicsStore) {
    store.record(turn("synthetic", 5, 100, 50, 3L to 2L))
    store.record(turn("synthetic", 7, 20, 10, 1L to 0L).copy(localStep = true))
}

internal val probePrice = TurnPrice(
    ModelCatalog(
        discoveryPrefix = "synthetic--",
        models = listOf(ModelEntry("synthetic", contextWindow = 1000, rates = ModelRates(3.0, 0.3, 15.0, 6.0))),
        defaultContextWindow = 1000,
    ),
)

internal fun store(
    dir: Path,
    clock: WallClock = WallClock { PROBE_HOUR + 123 },
    plans: AccountOnPlan = NoPlans,
): EconomicsStore = EconomicsStore(
    dir.resolve("economics.json"),
    probePrice,
    clock,
    log = { },
    plans = plans,
)

internal fun turn(
    model: String,
    input: Long,
    request: Long,
    upstream: Long,
    tools: Pair<Long, Long>,
) = TurnEconomics(
    model,
    TurnTokens(input, input / 2, input / 3, input * 2),
    TurnBytes(request, upstream),
    TurnTools(tools.first, tools.second),
    rateLimited = input > 0,
    history = UsageHistory(),
)

internal fun EconomicsStoreSource.rows(): List<EconomicsRow> =
    assertInstanceOf(EconomicsRead.Rows::class.java, read()).rows

internal fun EconomicsStoreSource.gap(): String =
    assertInstanceOf(EconomicsRead.Unavailable::class.java, read()).reason

internal fun head(key: String, economics: HeadEconomicsSource) =
    UsageHead(
        key,
        key,
        HeadUsageSource { UsageView(0, 0, null) },
        UsageHeadWarn(80, 0),
        sinks = UsageHeadSinks(economics = economics),
    )
