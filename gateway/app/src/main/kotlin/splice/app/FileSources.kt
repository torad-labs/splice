// NEW: adapters bridging the gateway's file stores to the control plane's read interfaces, so
// the dashboard reads the same on-disk truth the head writes (a DOWN head still shows state).
package splice.app

import splice.control.CompactView
import splice.control.HeadCompactSource
import splice.control.HeadLogSource
import splice.control.HeadUsageSource
import splice.control.RateLimitView
import splice.gateway.compact.CompactStats
import splice.gateway.usage.UsageStore
import java.nio.file.Files
import java.nio.file.Path

public class UsageStoreSource(private val store: UsageStore) : HeadUsageSource {
    private val state get() = store.readState()
    override fun outputTokens5h(): Long = state.outputTokens5h
    override fun entries(): Int = state.entries
    override fun ratelimit(): RateLimitView? =
        store.readRateLimit()?.let { RateLimitView(it.limitTokens, it.remainingTokens, it.resetTokens) }
}

public class CompactStatsSource(private val stats: CompactStats) : HeadCompactSource {
    override fun summary(tailN: Int): CompactView {
        val s = stats.read(tailN)
        val tail = s.tail.map { row -> row.mapValues { (_, v) -> v.toString() } }
        return CompactView(s.total, s.byOutcome, tail)
    }
}

public class LogFileSource(private val logFile: Path) : HeadLogSource {
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    override fun tail(lines: Int): String = try {
        if (!Files.exists(logFile)) {
            ""
        } else {
            Files.readAllLines(logFile).takeLast(lines).joinToString("\n")
        }
    } catch (e: Exception) {
        if (e is java.util.concurrent.CancellationException) throw e
        ""
    }

    override fun path(): String = logFile.toString()
}
