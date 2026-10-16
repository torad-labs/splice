// NEW: refreshes positive session-liveness evidence off the code-mode request and registry locks.
package splice.app.provider.codex

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splice.core.config.UserHome
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.provider.codex.CodeModeSessionAlive
import splice.provider.codex.CodeModeSessionIdle
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker

/** The daemon-owned scope polls registrations; a turn only reads the immutable published map.
 *  A stale registration still names a live process. Missing or unreadable evidence stays unknown. */
internal class CodeModeSessionLiveness(
    scope: CoroutineScope,
    private val source: SessionSource = SessionRegistry(
        UserHome.claudeDir().resolve("sessions"),
        RouteOfPid { SessionRoute.Unknown },
    ),
    private val ticker: Ticker = ProcessTicker(),
    private val ioDispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
    private val log: LogSink = LogSink { },
) : CodeModeSessionAlive {
    /** One poll's reading, published together so a turn never pairs one poll's liveness with another's idleness. */
    private data class Sample(val alive: Map<String, Boolean>, val idle: Set<String>)

    // The first sample is taken here, on the constructing thread. The registry's start trim reads this
    // port in its own init, right after the daemon builds the probe, and an unknown conversation is
    // evictable by design; a first sample launched onto the scope had not landed by then, so a live
    // conversation over its bounds was trimmed as unknown (2026-10-04).
    @Volatile private var latest = sample()

    /** Live sessions every registration of which says Claude Code sits at its prompt. Busy, waiting (a permission
     *  prompt) and shell sessions are not idle, and neither is a session the registry does not list. */
    val idle: CodeModeSessionIdle = CodeModeSessionIdle { sessionId -> sessionId in latest.idle }

    init {
        scope.launch {
            while (isActive && ticker.awaitTick(SESSION_POLL_MS)) {
                latest = withContext(ioDispatcher) { sample() }
            }
        }
    }

    override fun invoke(sessionId: String): Boolean? = latest.alive[sessionId]

    private fun sample(): Sample {
        val listing = Cancellables.runCatchingBestEffort { source.list() }.getOrElse { failure ->
            log("[code-mode] session liveness unavailable (${SafeFailureText.render(failure)}); remains unknown")
            return Sample(emptyMap(), emptySet())
        }
        if (listing.error != null) {
            log("[code-mode] session registry unavailable; liveness remains unknown")
            return Sample(emptyMap(), emptySet())
        }
        val bySession = listing.sessions.filter { it.sessionId != null && (it.process.pid ?: 0) > 0 }
            .groupBy { checkNotNull(it.sessionId) }
        val alive = bySession.mapValues { (_, sessions) ->
            sessions.any { it.availability != SessionAvailability.GONE }
        }
        val idle = bySession.filterValues { sessions ->
            val live = sessions.filter { it.availability == SessionAvailability.LIVE }
            live.isNotEmpty() && live.all { it.status.state == IDLE_STATE }
        }.keys
        return Sample(alive, idle)
    }
}

// Claude Code's own word, in its registration's status block, for a session at its prompt with no turn in flight.
private const val IDLE_STATE: String = "idle"

// Polling is daemon-owned background work, not a deadline on a script or an idle client.
private const val SESSION_POLL_MS: Long = 5_000
