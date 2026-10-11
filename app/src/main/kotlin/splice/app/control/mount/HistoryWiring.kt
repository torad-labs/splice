// NEW: Oct 10, 2026 — Settings > Your data's one history window, wired: the two calls the row
// makes, the scan cache they share, where a chosen window is persisted, and every other store the
// same save has to trim.
//
// Marlin's ruling, Oct 10: when a person says yes to "Delete 25,877 turns", everything history
// covers is gone at that moment — the request records, the hourly spending totals, and the message
// edges — trimmed at the same delete_before_epoch_ms by the same PUT. The stores take the new
// window live, with no restart, because someone who chose "Today only" for privacy and still saw
// last week's spend on Usage has been told something false.
package splice.app.control.mount

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.sources.EconomicsStoreSource
import splice.app.sources.PerfStatsSource
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.head.perf.HeldStore
import splice.head.perf.HeldStores
import splice.head.perf.HistoryDays
import splice.head.perf.HistoryRoutes
import splice.head.perf.HistoryStores
import splice.head.perf.HistoryWindowStore
import splice.head.usage.EconomicsStore
import splice.http.JsonReply
import splice.sessions.activity.SparedEdges

/** Settings > Your data's history row: what is held, what a shorter window would delete, and the
 *  save that deletes exactly that. */
internal class HistoryWiring(
    private val ports: ConsolePorts,
    private val config: ConfigService,
    private val guard: ControlGuard,
    private val fileIo: CoroutineDispatcher,
    /** Read at every save, never captured: the stores the HEADS write, not new ones over the same
     *  files, because the save has to trim the instances serving the console or the next read is
     *  served the old buckets. Taken from the live map so a head that joined after this mounted is
     *  trimmed too. */
    private val heads: Map<String, ManagedHead>,
) {
    // The scan cache lives here, across calls; the routes are built per call like every other
    // console port, so a port wired after mount is still read.
    private val inventory = HistoryDays()

    private val window = HistoryWindowStore { text ->
        val written = config.patch(mapOf(Knob.HISTORY_RETENTION_DAYS.key to text))
        written.rejected.values.firstOrNull() ?: written.notPersisted
    }

    // Everything else the window covers, cut at the same moment as the records, and the FIRST store
    // that could not be is named: a save that did not do all of what it said has to say so rather
    // than report a clean "Applied".
    private val stores = object : HistoryStores {
        override fun trimBefore(momentMs: Long): String? =
            hours(momentMs) ?: sessions(momentMs) ?: edges(momentMs) ?: kept()?.trimBefore(momentMs)

        override fun summariesHeld(): HeldStores = kept()?.summariesHeld() ?: HeldStores(emptyList(), null)

        // What the cut would take from the stores that are not turn records, measured as the read is drawn.
        override fun heldBefore(momentMs: Long): HeldStores {
            val kept = kept()?.heldBefore(momentMs) ?: HeldStores(emptyList(), null)
            val edges = edgesHeld(momentMs) ?: return kept
            val reasons = listOfNotNull(kept.unreadable, edges.unreadable)
            return HeldStores(kept.held + edges.held, reasons.joinToString("; ").ifEmpty { null })
        }
    }

    /** What the message edges would lose to the cut, which the record side of the page does not count. */
    private fun edgesHeld(momentMs: Long): HeldStores? {
        val store = ports.activity?.edges ?: return null
        return Cancellables.runCatchingCancellable { store.bytesBefore(momentMs, spared()) }.fold(
            { HeldStores(listOf(HeldStore("message_edges", it)), null) },
            { HeldStores(emptyList(), "message_edges: ${SafeFailureText.render(it)}") },
        )
    }

    private fun kept(): KeptHistoryStores? = ports.turnStatistics?.let { KeptHistoryStores(it) }

    private fun economics(): List<EconomicsStore> =
        heads.values.mapNotNull { (it.sources.economics as? EconomicsStoreSource)?.store }

    /** Every head's hourly spending totals, or the first one that could not be trimmed. */
    private fun hours(momentMs: Long): String? = economics().firstNotNullOfOrNull { store ->
        Cancellables.runCatchingCancellable { store.trimBefore(momentMs) }.exceptionOrNull()?.let {
            "the spending totals: ${SafeFailureText.render(it)}"
        }
    }

    /** What each finished session cost. Marlin, Oct 10, 2026: a session whose last activity is
     *  before the cut loses its total, and one working inside the window keeps it, because that is
     *  live work. Resumability is not the test: nearly every session is resumable while its
     *  transcript exists, so that exception would keep months of spend after "Today only". */
    private fun sessions(momentMs: Long): String? = heads.values
        .mapNotNull { (it.sources.perf as? PerfStatsSource)?.sessionTotals }
        .firstNotNullOfOrNull { store ->
            Cancellables.runCatchingCancellable { store.trimBefore(momentMs) }.exceptionOrNull()?.let {
                "what each session cost: ${SafeFailureText.render(it)}"
            }
        }

    /** The message edges, which are the record of who a person messaged, minus the ones an active
     *  team still needs (Marlin, Oct 10, 2026). An install with no activity stores has none. */
    private fun edges(momentMs: Long): String? {
        val store = ports.activity?.edges ?: return null
        return Cancellables.runCatchingCancellable { store.trimBefore(momentMs, spared()) }
            .exceptionOrNull()?.let { "the message edges: ${SafeFailureText.render(it)}" }
    }

    /** The sessions bound to a team that is still going: their edges are the record of a
     *  conversation still happening, so a cut leaves them where they are. */
    private fun spared(): SparedEdges {
        val live = ports.teams?.teams().orEmpty()
            .filterNot { it.archived }
            .flatMapTo(HashSet()) { team -> team.slots.mapNotNull { it.session } }
        return SparedEdges { edge -> edge.from in live || edge.toSession in live }
    }

    fun register(route: Route) {
        route.get("/api/history") {
            guard.guarded(call) {
                val days = call.request.queryParameters["days"]
                withContext(fileIo) { routes().read(config.getConfig().historyWindow, days) }.send(call)
            }
        }
        route.put("/api/history") {
            guard.guarded(call) {
                val asked = call.receiveText()
                withContext(fileIo) { routes().save(asked) }.send(call)
            }
        }
        route.get("/api/kept/stores") {
            guard.guarded(call) { withContext(fileIo) { storeRoutes()?.kept() ?: unwired }.send(call) }
        }
        route.delete("/api/kept/stores/{name}") {
            guard.guarded(call) {
                val name = call.parameters["name"].orEmpty()
                withContext(fileIo) { storeRoutes()?.delete(name) ?: unwired }.send(call)
            }
        }
    }

    /** The three stores no count reached before: the transcript copies, the parked compaction summaries and code mode
     *  work, each cleared by its own call. */
    private fun storeRoutes() = ports.turnStatistics?.let { state ->
        StoreKeptRoutes(
            mapOf(
                "transcript_copies" to TranscriptCopiesKept(state),
                "compaction_summaries" to CompactionSummariesKept(state),
                "code_mode" to CodeModeKept(state),
            ),
        )
    }

    private val unwired = JsonReply(HttpStatusCode.ServiceUnavailable, """{"error":"the state paths are not wired"}""")

    private fun routes() = HistoryRoutes(ports.turnStatistics, window, stores, inventory = inventory)
}
