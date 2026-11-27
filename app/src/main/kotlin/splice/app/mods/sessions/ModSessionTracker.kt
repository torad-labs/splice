package splice.app.mods.sessions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import splice.app.mods.events.ModEventText
import splice.app.mods.handover.MOD_HANDOVER_CAPACITY
import splice.app.mods.run.ModEventHub
import splice.core.util.WallClock
import splice.launch.LaunchNotice
import splice.launch.ModsOff
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessTicker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

// why: end a gone process promptly without waking a watcher for every session.
private const val SESSION_SWEEP_MS = 5_000L
private const val SESSION_START = "session.start"
private const val SESSION_FENCE_MS = 500L // why: only in-memory turn offers may still hold the fence

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
    private val started = ConcurrentHashMap<ModSessionPair, ModPendingSession>()
    private val early = ArrayDeque<ModEarlySession>()
    val confirmations: Collection<ModPendingSession> get() = started.values
    private var sweep: Job? = null

    private val writers = ReentrantReadWriteLock()

    @Volatile private var accepting = true

    @Volatile private var historyReady = true

    private var presence: StateFlow<Boolean>? = null
    val admitting: Boolean get() = accepting && presence?.value != false
    val observing: Boolean get() = admitting && historyReady

    fun follow(roster: StateFlow<Boolean>) {
        presence = roster
        historyReady = false
    }

    suspend fun restore(launches: ModLaunches, confirmed: List<ModSessionEntry> = emptyList()) {
        val index = ledger.restore(clock(), launches)
        currentCoroutineContext().ensureActive()
        if (!accepting) return
        for (id in index.ids) seen[id] = id in index.ownerless
        for (entry in index.active + confirmed) remember(entry, launches)
        while (accepting) {
            currentCoroutineContext().ensureActive()
            // Do not let a newer sighting take the start from one already on the pipe.
            val next = synchronized(early) {
                early.removeFirstOrNull().also { if (it == null) historyReady = true }
            } ?: break
            admit(next.sighting, launches, next)
        }
    }

    private fun remember(entry: ModSessionEntry, launches: ModLaunches) {
        val key = entry.pair
        seen.merge(key.id, key.owner == null) { old, fresh -> old || fresh }
        val pending = ModPendingSession(key, entry.context.off, lazyOf(entry))
        started[key] = pending
        val owner = key.owner ?: return
        pairs.getOrPut(key.id, ::mutableSetOf).add(owner)
        active.track(pending, launches.stored(owner))
        // Restored session.start entries may be hooks without an admitted turn.
        launches.forTurn(key.id, owner, served = false)
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
        withTimeoutOrNull(SESSION_FENCE_MS) { sweep?.join() }
        val fence = writers.writeLock()
        if (fence.tryLock(SESSION_FENCE_MS, TimeUnit.MILLISECONDS)) fence.unlock()
    }

    fun see(sighting: ModSessionSighting, launches: ModLaunches) {
        if (!admitting || !ledger.valid(sighting.id)) return
        if (!historyReady) {
            synchronized(early) {
                if (!historyReady) {
                    if (early.size == MOD_HANDOVER_CAPACITY) return
                    val ahead = ModEarlySession(sighting, launches)
                    early.addLast(ahead)
                    hub.offer(SESSION_START, null, ahead, ahead)
                    return
                }
            }
        }
        admit(sighting, launches)
    }

    private fun admit(sighting: ModSessionSighting, launches: ModLaunches, staged: ModEarlySession? = null) {
        val launch = sighting.owner?.let(launches::stored)
        if (launch == null && !hub.listening) return
        val read = writers.readLock()
        if (read.tryLock()) {
            try {
                if (admitting) seeFirst(sighting, launch, staged)
            } finally {
                read.unlock()
            }
        }
    }

    private fun seeFirst(sighting: ModSessionSighting, launch: StoredModLaunch?, staged: ModEarlySession?) {
        val known = ModSessionPair(sighting.id, sighting.owner)
        if (launch == null && started.containsKey(known)) return
        val key = ModSessionPair(sighting.id, launch?.launch?.owner)
        // Serialize the first offers for this id, so the first event says seenBefore=false even on a race.
        pairs.compute(key.id) { _, existing ->
            val owners = existing ?: mutableSetOf()
            if (key.owner !in owners && open(key, sighting, launch, staged)) {
                key.owner?.let(owners::add)
            }
            owners.takeIf { it.isNotEmpty() }
        }
    }

    /** One row per id even when multiple live launch processes are serving it. */
    fun active(): Map<String, ModLiveSession> = active.rows()

    private fun open(
        key: ModSessionPair,
        sighting: ModSessionSighting,
        launch: StoredModLaunch?,
        staged: ModEarlySession?,
    ): Boolean {
        val current = sighting.scrubber ?: text
        val at = sighting.at ?: clock()
        if (!current.safe(key.id)) return false
        val previous = if (key.owner == null) seen.put(key.id, true) else seen.putIfAbsent(key.id, false)
        if (key.owner == null && previous == true) return false
        val notice = launch?.launch
        val pending = ModPendingSession(
            key,
            notice?.settings?.modsOff,
            lazy { opening(key, at, notice, sighting.cwd, current.snapshot()) },
        )
        ledger.save(pending)
        if (hub.listening(SESSION_START, pending.off)) started[key] = pending
        val projection = ModSessionProjection(SESSION_START, pending, previous != null)
        if (staged == null) {
            hub.offer(SESSION_START, pending.off, projection = projection)
        } else {
            staged.projection = projection
        }
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
            if (!accepting) return@computeIfPresent owners
            started.remove(key)
            ledger.save(end)
            if (hub.listening("session.end", end.off)) {
                hub.offer("session.end", end.off, projection = ModSessionProjection("session.end", end, null))
            }
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
}

internal data class ModLiveSession(val labels: JsonObject, val off: ModsOff?, val launchedAt: Long)
