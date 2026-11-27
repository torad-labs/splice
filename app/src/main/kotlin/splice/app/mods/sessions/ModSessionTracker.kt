package splice.app.mods.sessions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val pairs = ConcurrentHashMap<String, MutableSet<String?>>()
    private val processes = ConcurrentHashMap<Long, MutableMap<ModSessionPair, SessionProcess>>()
    private val live = ConcurrentHashMap<ModSessionPair, ModLiveSession>()
    private var sweep: Job? = null

    @Volatile private var accepting = true

    fun restore(launches: ModLaunches) {
        seen.addAll(ledger.seen())
        for (entry in ledger.restore(clock(), launches)) {
            seen.add(entry.pair.id)
            pairs.getOrPut(entry.pair.id, ::mutableSetOf).add(entry.pair.owner)
            if (!entry.ended) {
                val launch = entry.pair.owner?.let(launches::stored)
                track(entry, launch)
                launches.forTurn(entry.pair.id, entry.pair.owner, served = false)
            }
        }
    }

    fun start(scope: CoroutineScope) {
        sweep = scope.launch {
            while (ticker.awaitTick(SESSION_SWEEP_MS)) endGone()
        }
    }

    suspend fun stop() {
        accepting = false
        sweep?.cancel()
        sweep?.join()
    }

    fun see(
        id: String,
        launch: StoredModLaunch?,
        at: Long = clock(),
        cwd: String? = null,
        scrubber: ModEventText? = null,
    ) {
        if (!accepting || !ledger.valid(id)) return
        if (launch == null && !hub.listening) return
        val key = ModSessionPair(id, launch?.launch?.owner)
        // Serialize the first offers for this id, so the first event says seenBefore=false even on a race.
        pairs.compute(id) { _, existing ->
            val owners = existing ?: mutableSetOf()
            if (key.owner !in owners && open(key, at, launch, cwd, scrubber)) owners.add(key.owner)
            owners.takeIf { it.isNotEmpty() }
        }
    }

    /** One row per id even when multiple live launch processes are serving it. */
    fun active(): Map<String, ModLiveSession> {
        val rows = mutableMapOf<String, ModLiveSession>()
        for ((key, row) in live) {
            rows.merge(key.id, row) { old, new -> if (old.launchedAt < new.launchedAt) new else old }
        }
        return rows
    }

    private fun open(
        key: ModSessionPair,
        at: Long,
        launch: StoredModLaunch?,
        cwd: String?,
        scrubber: ModEventText?,
    ): Boolean {
        val current = scrubber ?: text.snapshot()
        if (!current.safe(key.id)) return false
        val seenBefore = !seen.add(key.id)
        val context = ModSessionContext(
            current.text(launch?.launch?.cwd ?: cwd.orEmpty()),
            current.labels(launch?.launch?.settings?.labels.orEmpty()),
            launch?.launch?.settings?.modsOff,
        )
        val entry = ModSessionEntry(key, at, context)
        ledger.save(entry)
        offer("session.start", entry, seenBefore)
        track(entry, launch)
        return true
    }

    private fun track(entry: ModSessionEntry, launch: StoredModLaunch?) {
        val pid = launch?.launch?.pid ?: return
        val birth = launch.processStart ?: return
        val process = SessionProcess(birth, entry)
        processes.compute(pid) { _, entries ->
            live[entry.pair] = ModLiveSession(entry.context.labels, entry.context.off, launch.recordedAt)
            (entries ?: mutableMapOf()).apply { put(entry.pair, process) }
        }
    }

    private fun endGone() {
        val ended = mutableListOf<ModSessionEntry>()
        for (pid in processes.keys) {
            val birth = ModLaunchProcess.birth(pid)
            processes.computeIfPresent(pid) { _, entries -> endPairs(entries, birth, ended) }
        }
        // Opening holds the session key before the pid key. Close it only after releasing the pid key.
        ended.forEach(::finish)
    }

    private fun endPairs(
        entries: MutableMap<ModSessionPair, SessionProcess>,
        birth: String?,
        ended: MutableList<ModSessionEntry>,
    ): MutableMap<ModSessionPair, SessionProcess>? {
        entries.entries.removeIf { (key, process) ->
            if (process.birth == birth) return@removeIf false
            ended.add(process.entry.copy(at = clock(), ended = true))
            live.remove(key)
            true
        }
        return entries.takeIf { it.isNotEmpty() }
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

    private data class SessionProcess(val birth: String, val entry: ModSessionEntry)
}

internal data class ModLiveSession(val labels: JsonObject, val off: ModsOff?, val launchedAt: Long)
