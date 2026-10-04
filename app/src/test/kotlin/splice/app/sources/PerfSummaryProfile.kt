// NEW: compare the actual pooled summary assembly with the former envelope over identical source facts.
package splice.app.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeads
import splice.usage.perf.PerfPayloads
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

internal class PerfSummaryProfile(private val source: PerfRowsSource) {
    private val now = SCALE_SINCE + 7L * 24 * 60 * 60 * 1_000
    private val clock = WallClock { now }
    private val windowType = Class.forName("splice.usage.perf.PerfWindow")
    private val window = requireNotNull(windowType.enumConstants.singleOrNull { it.toString() == "D7" })
    private val summaryType = Class.forName("splice.usage.perf.PerfSummary")
    private val summary = summaryType.getDeclaredConstructor(WallClock::class.java).newInstance(clock)
    private val fold = summaryType.getDeclaredMethod(
        "json",
        PerfRowsWindow::class.java,
        windowType,
        Long::class.javaPrimitiveType,
        MutableList::class.java,
    )
    private val heads = UsageHeads {
        listOf(
            UsageHead(
                "synthetic",
                "claude-synthetic",
                HeadUsageSource { UsageView(0, 0, null) },
                80,
                0,
                perfRows = source,
            ),
        )
    }
    private val payloads = PerfPayloads(heads, clock)
    private val assemble = PerfPayloads::class.java.methods.single {
        it.name.startsWith("summaryJson") && it.parameterTypes.contentEquals(arrayOf(windowType))
    }

    internal fun pooled(): String = requireNotNull(assemble.invoke(payloads, window) as? String)

    internal fun perHead(): String = buildJsonObject {
        put("window", "7d")
        putJsonArray("heads") {
            heads.all().forEach { head ->
                val read = source.window(SCALE_SINCE)
                // Only the old envelope is reproduced. The real fold runs with no pooled collector.
                val json = requireNotNull(fold.invoke(summary, read, window, now, null) as? JsonObject)
                addJsonObject {
                    put("key", head.key)
                    put("label", head.label)
                    json.forEach { (key, value) -> put(key, value) }
                }
            }
        }
    }.toString()
}
