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
            active.track(entry, entry.pair.owner?.let(launches::stored))
            launches.forTurn(entry.pair.id, entry.pair.owner)
        }
        historyReady = true
    }

    fun start(scope: CoroutineScope) {
        sweep = scope.launch { sweep() }
    }

    suspend fun sweep() {
        while (ticker.awaitTick(SESSION_SWEEP_MS)) {
            if (observing && hub.listening) active.expired(clock()).forEach(::finish)
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
        val current = scrubber ?: text.snapshot()
        if (!current.safe(key.id)) return false
        val previous = if (key.owner == null) seen.put(key.id, true) else seen.putIfAbsent(key.id, false)
        if (key.owner == null && previous == true) return false
        val seenBefore = previous != null
        val context = ModSessionContext(
            current.text(launch?.launch?.cwd ?: cwd.orEmpty()),
            current.labels(launch?.launch?.settings?.labels.orEmpty()),
            launch?.launch?.settings?.modsOff,
        )
        val entry = ModSessionEntry(key, at, context)
        ledger.save(entry)
        offer("session.start", entry, seenBefore)
        active.track(entry, launch)
        return true
    }

    private fun finish(entry: ModSessionEntry) {
        val key = entry.pair
        pairs.computeIfPresent(key.id) { _, owners ->
            ledger.save(entry)
            offer("session.end", entry)
            owners.remove(key.owner)
            owners.takeIf { it.isNotEmpty() }
        }
    }

    private fun offer(name: String, entry: ModSessionEntry, seenBefore: Boolean? = null) {
        val context = entry.context
        if (!hub.listening(name, context.off)) return
        val id = entry.pair.id
        val scrubber = if (entry.ended) text.snapshot() else text
        if (!scrubber.safe(id)) return
        hub.offer(
            ModEvent(
                name,
                id,
                buildJsonObject {
                    put("session", id)
                    put("cwd", scrubber.text(context.cwd))
                    put("labels", scrubber.labels(context.labels))
                    if (seenBefore != null) put("seenBefore", seenBefore)
                },
            ),
            context.off,
        )
    }
}

internal data class ModLiveSession(val labels: JsonObject, val off: ModsOff?, val launchedAt: Long)
