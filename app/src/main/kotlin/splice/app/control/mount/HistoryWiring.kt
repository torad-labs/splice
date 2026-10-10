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

import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.sources.EconomicsStoreSource
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.head.perf.HistoryDays
import splice.head.perf.HistoryRoutes
import splice.head.perf.HistoryStores
import splice.head.perf.HistoryWindowStore
import splice.head.usage.EconomicsStore

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

    // Every head's totals are trimmed, and the FIRST store that could not be is named: a save that
    // did not do all of what it said has to say so rather than report a clean "Applied".
    private val stores = HistoryStores { moment ->
        economics().firstNotNullOfOrNull { store ->
            Cancellables.runCatchingCancellable { store.trimBefore(moment) }.exceptionOrNull()?.let {
                "the spending totals: ${SafeFailureText.render(it)}"
            }
        }
    }

    private fun economics(): List<EconomicsStore> =
        heads.values.mapNotNull { (it.sources.economics as? EconomicsStoreSource)?.store }

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
    }

    private fun routes() = HistoryRoutes(ports.turnStatistics, window, stores, inventory = inventory)
}
