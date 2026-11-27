package splice.app.mods.sessions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.mods.events.ModEventText
import splice.app.mods.run.ModEvent
import splice.app.mods.run.ModEventHub
import splice.core.util.WallClock
import splice.launch.LaunchNotice
import splice.launch.ModsOff
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessTicker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

// why: end a gone process promptly without waking a watcher for every session.
private const val SESSION_SWEEP_MS = 5_000L

/** The session id names history; a launch names the lifetime of one process serving it. */
internal class ModSessionTracker(
    private val hub: ModEventHub,
    private val ledger: ModSessionLedger,
    private val text: ModEventText,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val ticker: Ticker = ProcessTicker(),
) {
    // One entry per historical id. The flag remembers an ownerless start without retaining a synthetic pair.
    private val seen = ConcurrentHashMap<String, Boolean>()
    private val pairs = ConcurrentHashMap<String, MutableSet<String?>>()
    private val active = ModActiveSessions()
    private var sweep: Job? = null

    private val writers = ReentrantReadWriteLock()

    @Volatile private var accepting = true

    @Volatile private var historyReady = true

    private var presence: StateFlow<Boolean>? = null
    val observing: Boolean get() = accepting && historyReady && presence?.value != false

    fun follow(roster: StateFlow<Boolean>) {
        presence = roster
        historyReady = false
    }

    suspend fun restore(launches: ModLaunches) {
        val index = ledger.restore(clock(), launches)
        for (id in index.ids) seen[id] = id in index.ownerless
        for (entry in index.active) {
            pairs.getOrPut(entry.pair.id, ::mutableSetOf).add(entry.pair.owner)
            val pending = ModPendingSession(entry.pair, entry.context.off, lazyOf(entry))
            active.track(pending, entry.pair.owner?.let(launches::stored))
            // Restored session.start entries may be hooks without an admitted turn.
            launches.forTurn(entry.pair.id, entry.pair.owner, served = false)
        }
        historyReady = true
    }

    fun start(scope: CoroutineScope) {
        sweep = scope.launch { sweep() }
    }

    suspend fun sweep() {
        while (ticker.awaitTick(SESSION_SWEEP_MS)) {
            if (observing && hub.listening) active.expired().forEach(::finish)
        }
    }

    suspend fun stop() {
        accepting = false
        sweep?.cancel()
        sweep?.join()
        writers.write { /* A start already admitted must queue its ledger write before lock release. */ }
    }

    fun see(
        id: String,
        launch: StoredModLaunch?,
        at: Long = clock(),
        cwd: String? = null,
        scrubber: ModEventText? = null,
    ) {
        if (!observing || !ledger.valid(id)) return
        if (launch == null && !hub.listening) return
        val read = writers.readLock()
        if (read.tryLock()) {
            try {
                if (observing) seeFirst(ModSessionPair(id, launch?.launch?.owner), at, launch, cwd, scrubber)
            } finally {
                read.unlock()
            }
        }
    }

    private fun seeFirst(
        key: ModSessionPair,
        at: Long,
        launch: StoredModLaunch?,
        cwd: String?,
        scrubber: ModEventText?,
    ) {
        // Serialize the first offers for this id, so the first event says seenBefore=false even on a race.
        pairs.compute(key.id) { _, existing ->
            val owners = existing ?: mutableSetOf()
            if (key.owner !in owners && open(key, at, launch, cwd, scrubber)) {
                key.owner?.let(owners::add)
            }
            owners.takeIf { it.isNotEmpty() }
        }
    }

    /** One row per id even when multiple live launch processes are serving it. */
    fun active(): Map<String, ModLiveSession> = active.rows()

    private fun open(
        key: ModSessionPair,
        at: Long,
        launch: StoredModLaunch?,
        cwd: String?,
        scrubber: ModEventText?,
    ): Boolean {
        val current = scrubber ?: text
        if (!current.safe(key.id)) return false
        val previous = if (key.owner == null) seen.put(key.id, true) else seen.putIfAbsent(key.id, false)
        if (key.owner == null && previous == true) return false
        val notice = launch?.launch
        val pending = ModPendingSession(
            key,
            notice?.settings?.modsOff,
            lazy { opening(key, at, notice, cwd, current.snapshot()) },
        )
        ledger.save(pending)
        offer("session.start", pending, previous != null)
        active.track(pending, launch)
        return true
    }

    private fun opening(
        key: ModSessionPair,
        at: Long,
        launch: LaunchNotice?,
        cwd: String?,
        live: ModEventText,
    ): ModSessionEntry? {
        if (!live.safe(key.id)) return null
        val context = ModSessionContext(
            live.text(launch?.cwd ?: cwd.orEmpty()),
            live.labels(launch?.settings?.labels.orEmpty()),
            launch?.settings?.modsOff,
        )
        return ModSessionEntry(key, at, context)
    }

    private fun finish(start: ModPendingSession) {
        val key = start.pair
        val at = clock()
        val end = ModPendingSession(key, start.off, lazy { ended(start.entry, at) })
        pairs.computeIfPresent(key.id) { _, owners ->
            ledger.save(end)
            offer("session.end", end)
            owners.remove(key.owner)
            owners.takeIf { it.isNotEmpty() }
        }
    }

    private fun ended(start: ModSessionEntry?, at: Long): ModSessionEntry? {
        val entry = start ?: return null
        val live = text.snapshot()
        if (!live.safe(entry.pair.id)) return null
        val context = entry.context
        return entry.copy(
            at = at,
            context = context.copy(cwd = live.text(context.cwd), labels = live.labels(context.labels)),
            ended = true,
        )
    }

    private fun offer(name: String, pending: ModPendingSession, seenBefore: Boolean? = null) {
        if (!hub.listening(name, pending.off)) return
        hub.offer(name, pending.off) {
            pending.entry?.let { entry ->
                val id = entry.pair.id
                ModEvent(
                    name,
                    id,
                    buildJsonObject {
                        put("session", id)
                        put("cwd", entry.context.cwd)
                        put("labels", entry.context.labels)
                        if (seenBefore != null) put("seenBefore", seenBefore)
                    },
                )
            }
        }
    }
}

internal data class ModLiveSession(val labels: JsonObject, val off: ModsOff?, val launchedAt: Long)
