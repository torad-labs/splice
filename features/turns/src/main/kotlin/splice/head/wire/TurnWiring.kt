package splice.head.wire

import splice.core.model.UsageSizing
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.head.usage.TurnUsage
import splice.head.usage.UsageHud
import java.util.concurrent.ConcurrentHashMap

/** One usage payload for streamed and collected replies. Raw upstream usage stays in the proxy's own accounting. */
internal class TurnWiring(
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    private val hud = UsageHud()

    // A changed factor is useful to log, but not once per turn.
    private val saidScales: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun usagePayloadBuilder(model: String, sizing: UsageSizing): UsagePayloadBuilder {
        if (sizing.factor != 1.0) sayScale(model, sizing)
        return { usage ->
            // A code-mode step with no measured input still carries the last measured context, not the last output.
            val context = usage?.origin?.clientContext?.takeIf { usage.inputTokens == 0L } ?: usage
            // Anthropic's input and cache buckets are disjoint; OpenAI includes cached input in its total.
            val cached = context?.cachedTokens ?: 0
            val written = context?.cacheWriteTokens ?: 0
            val nonCachedInput = ((context?.inputTokens ?: 0) - cached - written).coerceAtLeast(0)
            hud.buildUsagePayload(
                TurnUsage(
                    sizing.scale(nonCachedInput),
                    usage?.outputTokens ?: 0,
                    sizing.scale(written),
                    sizing.scale(cached),
                    sizing.scale(context?.cacheWrite?.hourlyTokens ?: 0),
                ),
                sizing.contextWindow,
            )
        }
    }

    private fun sayScale(model: String, sizing: UsageSizing) {
        val line = "[usage] row '$model' reports client-scaled tokens " +
            "(factor ${sizing.factor}, client window ${sizing.contextWindow})\n"
        if (saidScales.size >= MAX_SAID_SCALES) saidScales.clear()
        if (saidScales.add(line)) log(line)
    }
}

private const val MAX_SAID_SCALES = 256
