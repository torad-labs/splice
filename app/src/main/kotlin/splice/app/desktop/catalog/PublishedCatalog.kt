// NEW: 2026-11-20 — the model catalog as the door serves it: one snapshot of what the signed-in providers publish
// (CatalogBuild), built at the daemon's start and again when a provider's credential changes (a sign-in, a sign-out, a key
// set), written to `model-catalog.json` in the state folder (CatalogFile) and served from memory to every list the door
// gives: gateway discovery, the terminal's picker, another machine's picker, `splice desktop`, `splice claude` and
// `/api/models` (DoorCatalog). No request builds it: until Nov 20, 2026 each of those built its own list from the
// providers at each call.
package splice.app.desktop.catalog

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The catalog published last ([current]), built by [build] and kept in [file]. */
internal class PublishedCatalog(
    private val build: CatalogBuild,
    private val file: CatalogFile,
    private val io: CoroutineDispatcher,
) {
    private val lock = Mutex()

    /** The catalog published last; empty before the first [publish]. */
    @Volatile
    var current: ProviderCatalog = ProviderCatalog()
        private set

    /** The catalog of the providers signed in to [lineup], each provider's models in splice.toml's order, written and
     *  then served. Two publications never interleave, so the file and what is served are the same catalog. */
    suspend fun publish(lineup: DesktopLineup) = lock.withLock {
        val signedIn = (lineup.providers + lineup.desktopOnly)
            .map { CatalogSource(it.key, it.catalog, it.effortVocabulary) }
        val catalog = build.of(signedIn)
        withContext(io) { file.keep(catalog) }
        current = catalog
    }
}
