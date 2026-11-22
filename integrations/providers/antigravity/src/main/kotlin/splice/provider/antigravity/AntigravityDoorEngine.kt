// NEW: desktop-antigravity (door chat). Bounded pool of `agy acp` sessions backing Claude Desktop
// Chat requests arriving at Splice's HTTP `/door/v1/messages`. Keeps conversations warm across
// turns (including tool_use -> tool_result pause-and-resume), evicts idle sessions, and closes
// every child process cleanly on daemon shutdown.
package splice.provider.antigravity

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.util.concurrent.ConcurrentHashMap

/** Streaming output sink for one HTTP turn driven by [AntigravityDoorEngine]. */
public interface DoorAgySink {
    public suspend fun onThinking(text: String)

    public suspend fun onText(text: String)

    public suspend fun onToolUse(id: String, name: String, inputJson: String)
}

/** Terminal result of one HTTP turn driven by [AntigravityDoorEngine]. */
public sealed class DoorAgyTurnResult {
    public data class Completed(
        val hasToolUse: Boolean,
        val incomplete: Boolean,
        val inputTokens: Long,
        val outputTokens: Long,
    ) : DoorAgyTurnResult()

    public data class Failed(val message: String) : DoorAgyTurnResult()
}

/** Pooled `agy acp` turn engine for Claude Desktop Chat requests through Splice's door. */
public class AntigravityDoorEngine(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val log: LogSink,
    private val agy: List<String> = agyCommand,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val idleTtlMs: Long = DEFAULT_IDLE_TTL_MS,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) : AutoCloseable {
    private val poolLock = Mutex()
    private val sessions = ConcurrentHashMap<String, DoorAgySession>()

    @Volatile
    private var closed = false

    /** Runs one Anthropic Messages request against a pooled `agy acp` session. */
    public suspend fun serve(
        request: JsonObject,
        upstreamModel: String,
        sink: DoorAgySink,
        sessionHint: String? = null,
    ): DoorAgyTurnResult {
        if (closed) return DoorAgyTurnResult.Failed("antigravity door engine is closed")
        val ask = DoorAgyRequestParser.parse(request, upstreamModel, sessionHint)
        val session = acquire(ask)
        val result = session.runTurn(ask, sink)
        if (session.dead) removeIfSame(ask.conversationKey, session)
        return result
    }

    override fun close() {
        closed = true
        val keys = sessions.keys.toList()
        keys.forEach { key -> sessions.remove(key)?.close() }
    }

    private suspend fun acquire(ask: DoorAgyAsk): DoorAgySession {
        val toClose = mutableListOf<DoorAgySession>()
        val chosen = poolLock.withLock {
            val now = clock()
            evictExpiredLocked(now, toClose)
            val existing = sessions[ask.conversationKey]
            if (existing != null && !existing.needsFreshSession(ask)) {
                existing
            } else {
                existing?.let {
                    sessions.remove(ask.conversationKey)
                    toClose += it
                }
                while (sessions.size >= maxSessions) {
                    val oldestKey = sessions.entries.minByOrNull { it.value.lastUsedMs }?.key ?: break
                    sessions.remove(oldestKey)?.let { toClose += it }
                }
                val created = DoorAgySession(agy, scope, io, log, ask.tools, clock)
                sessions[ask.conversationKey] = created
                created
            }
        }
        toClose.forEach { it.close() }
        return chosen
    }

    private fun evictExpiredLocked(now: Long, toClose: MutableList<DoorAgySession>) {
        val expired = sessions.entries
            .filter { (_, s) -> s.dead || now - s.lastUsedMs > idleTtlMs }
            .map { it.key }
        expired.forEach { key -> sessions.remove(key)?.let { toClose += it } }
    }

    private suspend fun removeIfSame(key: String, target: DoorAgySession) {
        val removed = poolLock.withLock {
            if (sessions[key] === target) sessions.remove(key) else null
        }
        removed?.close()
    }
}

private const val DEFAULT_MAX_SESSIONS = 8
private const val DEFAULT_IDLE_TTL_MS = 10 * 60_000L
