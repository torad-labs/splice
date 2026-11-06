package splice.observability.export

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private data class PrometheusSample(val text: String, val at: Long, val until: Long)
private data class PrometheusState(
    val phase: String,
    val sample: PrometheusSample? = null,
    val attemptedAt: Long? = null,
)

internal class PrometheusScrapes(enabled: Boolean, private val clock: WallClock) : AutoCloseable {
    private val current = AtomicReference(PrometheusState(if (enabled) "unsampled" else "disabled"))
    private val dropped = AtomicLong()
    private val oversized = AtomicLong()
    private val responseBusy = AtomicLong()

    fun publish(points: List<PerfAggregatePoint>, totals: ExportTotals): Boolean {
        val lease = PerfWorkBudget.acquire(RENDER_BYTES) ?: return fail("busy")
        var retained = false
        return try {
            val text = PrometheusText.render(points, totals.dropped, totals.missingMs) ?: return fail("oversize")
            val sample = PrometheusSample(text, clock(), totals.asOfMs)
            lease.retain(sample, text.length * CHARACTER_BYTES + OVERHEAD_BYTES)
            retained = true
            current.getAndSet(PrometheusState("ready", sample, sample.at)).sample?.let(PerfWorkBudget::release)
            true
        } finally {
            if (!retained) lease.close()
        }
    }

    fun fail(reason: String): Boolean {
        dropped.incrementAndGet()
        if (reason == "oversize") oversized.incrementAndGet()
        current.updateAndGet { it.copy(phase = reason, attemptedAt = clock()) }
        return false
    }

    fun scrape(): PerfScrape {
        val lease = responseLease() ?: return ScrapeUnavailable.of("busy")
        val view = current.get()
        val phase = phase(view)
        if (phase != "ready") { lease.close(); return ScrapeUnavailable.of(phase) }
        val sample = requireNotNull(view.sample)
        val result = PerfScrape(sample.text, phase, sample.at, sample.until)
        lease.retain(result, PerfScrapeLimits.RESPONSE_BYTES)
        return result
    }

    /** Compatibility callers own the returned String and release it with PerfWorkBudget.release. */
    fun text(): String = scrape().use { snapshot ->
        if (!snapshot.available) return@use snapshot.text
        val lease = responseLease() ?: return@use ScrapeUnavailable.of("busy").text
        val copy = String(snapshot.text.toByteArray(Charsets.US_ASCII), Charsets.US_ASCII)
        lease.retain(copy, PerfScrapeLimits.RESPONSE_BYTES)
        copy
    }

    private fun responseLease(): PerfWorkBudget.Lease? = PerfWorkBudget.acquire(PerfScrapeLimits.RESPONSE_BYTES)
        .also { if (it == null) responseBusy.incrementAndGet() }

    private fun phase(view: PrometheusState): String = if (
        view.phase == "ready" && clock() - requireNotNull(view.sample).at > PerfScrapeLimits.MAX_AGE_MS
    ) {
        "stale"
    } else {
        view.phase
    }

    fun status(): JsonObject {
        val view = current.get()
        return buildJsonObject {
            put("state", phase(view))
            put("max_text_bytes", PerfScrapeLimits.MAX_TEXT_BYTES)
            put("response_bytes", PerfScrapeLimits.RESPONSE_BYTES)
            put("max_age_ms", PerfScrapeLimits.MAX_AGE_MS)
            view.attemptedAt?.let { put("last_attempt_epoch_ms", it) }
            view.sample?.let {
                put("last_success_epoch_ms", it.at)
                put("source_until_ms", it.until)
                put("sample_age_ms", (clock() - it.at).coerceAtLeast(0))
                put("text_bytes", it.text.length)
            }
            put("render_dropped", dropped.get())
            put("render_oversize", oversized.get())
            put("response_busy", responseBusy.get())
        }
    }

    override fun close() {
        current.getAndSet(PrometheusState("closed")).sample?.let(PerfWorkBudget::release)
    }
}

private const val CHARACTER_BYTES = 2L
private const val OVERHEAD_BYTES = 4_096L
private const val RENDER_BYTES = 3L * PerfScrapeLimits.MAX_TEXT_BYTES + 65_536
