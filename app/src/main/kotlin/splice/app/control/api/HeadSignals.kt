// NEW: the per-head readings a head's own health does not carry, read per request: a runtime that did not answer at
// the last background probe (V4-417), a provider refusing turns until a known instant (V4-398), and a window fully
// used (V4-452). /health and the heads route both read them, so they live in one place.
package splice.app.control.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.control.ManagedHead
import splice.app.control.RuntimeNotAnswering
import splice.app.control.RuntimeUnread
import splice.core.usage.QuotaFull
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionSource
import java.util.concurrent.TimeUnit

internal class HeadSignals(
    private val heads: Map<String, ManagedHead>,
    private val runtimeNotAnswering: RuntimeNotAnswering,
    /** Who runs on which head now; null names no client pids. */
    private val sessions: SessionSource? = null,
    private val runtimeUnread: RuntimeUnread = RuntimeUnread { emptySet() },
) {
    /** For each head, the pids of the Claude Code clients its live sessions run in, from the session registry. */
    fun clientPids(): Map<String, List<Long>> = sessions?.read().orEmpty()
        .filter { it.availability == SessionAvailability.LIVE }
        .mapNotNull { record -> record.head?.let { head -> record.process.pid?.let { head to it } } }
        .groupBy({ it.first }, { it.second })

    /** V4-417: the local heads whose runtime is silent, from the daemon's held probe. */
    fun silentRuntimes(): Map<String, String> = runtimeNotAnswering()

    /** The local heads with no runtime reading yet, or none any more: neither answering nor silent. */
    fun unreadRuntimes(): Set<String> = runtimeUnread()

    /** V4-398: for each running head whose provider refuses turns until a known instant, that instant in epoch
     *  seconds. Read per request, so a head that recovers drops out on the next probe. */
    fun quotaResets(nowEpochMillis: Long = System.currentTimeMillis()): Map<String, Long> =
        heads.filterValues { it.head.healthSnapshot().running }.mapNotNull { (key, managed) ->
            managed.head.providerResetForMs().takeIf { it > 0L }
                ?.let { key to TimeUnit.MILLISECONDS.toSeconds(nowEpochMillis + it) }
        }.toMap()

    /** V4-452: for each running head whose provider's current reading names a window fully used, that reading.
     *  Never a refusal ([quotaResets] is), so /health and the heads route carry it under its own name. */
    fun quotaFull(): Map<String, QuotaFull> =
        heads.filterValues { it.head.healthSnapshot().running }
            .mapNotNull { (key, managed) -> managed.head.quotaFull()?.let { key to it } }.toMap()

    /** One head's full reading as the wire carries it, on /health and on the heads route alike. */
    fun quotaFullJson(reading: QuotaFull): JsonObject = buildJsonObject {
        put("window", reading.window.wire)
        put("resetsAtEpochSeconds", reading.resetsAtEpochSeconds)
    }
}
