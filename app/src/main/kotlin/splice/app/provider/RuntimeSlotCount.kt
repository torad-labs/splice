// NEW: V4-166 (review, 2026-09-19) — a llama-server's slot count, kept OFF the request path, and one
// slot table per runtime.
//
// V4-165 read /props inside buildTurn: a call thread holding one of the process-wide materialization
// permits (RequestMaterializationGate) waited on it, up to JdkLocalHttp's connect and probe timeouts
// against a runtime that drops packets, on every turn until a read succeeded. Once one did, the count
// was kept forever, so a server restarted with another -np had conversations pinned to ids it wraps
// (id_slot % n, server-context.cpp:1433) onto slots other conversations hold. And each head, and each
// topology reload, built its own table, so two heads on one runtime, or a reload with turns in
// flight, leased one slot twice. Here a turn reads the last answer and never waits; an answer older
// than SLOT_COUNT_REFRESH_MS starts one read in the background; each change in what the runtime answers is
// logged once; and SlotTables hands every head on a runtime the same table, across reloads.
package splice.app.provider

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.MonoClock
import splice.dialect.chat.SlotAffinity
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.local.LlamaServerSlots
import splice.upstream.local.SlotsReading
import splice.upstream.transport.LocalHttp
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The slot count a turn reads: the runtime's last answer, refreshed behind the turns, never waited on. */
internal class RuntimeSlotCount(
    private val tag: String,
    private val slots: LlamaServerSlots,
    private val background: Background,
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
) : SlotAffinity.SlotCount {

    /** Where the reads run and what they report to: the daemon's scope, off the call threads. */
    data class Background(
        val scope: CoroutineScope,
        val log: LogSink,
        val dispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
    )

    private val reading = AtomicReference<SlotsReading?>(null)
    private val refreshing = AtomicBoolean(false)

    @Volatile private var readAtMs: Long? = null

    override fun read(): Int? {
        val last = readAtMs
        if (last == null || clock() - last >= SLOT_COUNT_REFRESH_MS) refresh()
        return (reading.get() as? SlotsReading.Count)?.slots
    }

    /** One read in the background, unless one is already running. */
    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        background.scope.launch(background.dispatcher) {
            try {
                val now = slots.read()
                if (reading.getAndSet(now) != now) background.log(describe(now))
            } finally {
                readAtMs = clock()
                refreshing.set(false)
            }
        }
    }

    private fun describe(now: SlotsReading): String = when (now) {
        is SlotsReading.Count ->
            "[$tag] slot affinity: the runtime runs ${now.slots} slots; each conversation keeps its own\n"
        is SlotsReading.Unreadable ->
            "[$tag] slot affinity paused: ${now.why} — turns go out unpinned until it answers with a slot count\n"
    }
}

// why: how long a changed -np can go unseen, so how long a restarted server can be handed slot ids it
// wraps; a read is one local GET that takes no server lock (server-context.cpp:4716).
private const val SLOT_COUNT_REFRESH_MS = 10_000L

/** One slot table per runtime: every head on it shares it, and a topology reload finds it again with
 *  the leases its in-flight turns still hold. A new bearer reads /props differently, so it starts a
 *  new table rather than reuse a reader built for the old one. */
internal class SlotTables(private val background: RuntimeSlotCount.Background) {
    private val tables = ConcurrentHashMap<String, SlotAffinity>()

    fun forRuntime(tag: String, baseUrl: String, bearer: String?, http: LocalHttp): SlotAffinity =
        tables.computeIfAbsent("${baseUrl.trimEnd('/')} ${bearer.orEmpty()}") {
            // Read now, so the count is known by the first turn instead of learned from it.
            val count = RuntimeSlotCount(tag, LlamaServerSlots(baseUrl, http), background).also { it.refresh() }
            SlotAffinity(count)
        }
}
