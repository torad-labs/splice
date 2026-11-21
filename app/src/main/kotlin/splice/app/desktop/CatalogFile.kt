// NEW: 2026-11-20 — the model catalog's file, StatePaths.modelCatalogFile (`model-catalog.json` in splice's state
// folder): the catalog as it was last built, written again at every daemon start/restart and whenever a sign-in, a
// sign-out or a provider's list changes it.
package splice.app.desktop

import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Path

internal class CatalogFile(file: Path, private val log: LogSink) :
    StateRecords<ProviderCatalog>(file, ProviderCatalog.serializer(), ProviderCatalog(), "models", log) {
    /** [catalog] kept as the file's content. A disk that refuses it is said in the log, and the catalog is served from
     *  memory all the same. */
    fun keep(catalog: ProviderCatalog) {
        try {
            update { catalog }
        } catch (e: IOException) {
            log("[models] the model catalog was not written: ${SafeFailureText.render(e)}\n")
        }
    }
}
