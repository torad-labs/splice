// NEW: the rows a local runtime is asked about, over one catalog (LAYOUT-01). The boot refusal
// (app's LocalProbeInputs) and doctor (features/diagnostics) both ask a local runtime whether it
// serves each upstream id at the window the catalog advertises. The computation left app's provider
// wiring so doctor could read it from its feature; it is its own type rather than a sixteenth
// ModelCatalog function because the catalog already sits at detekt's per-class function ceiling.
package splice.core.model

/** Upstream id -> the window [catalog] advertises for it, over its picker rows and extra windows. */
public class UpstreamWindows(private val catalog: ModelCatalog) {

    /** Suffixes stripped (the wire sees the bare id); two rows over one id keep the wider window. */
    public fun byId(): Map<String, Long> {
        val rows = catalog.models.map { catalog.stripSuffixes(it.id) to it.contextWindow } +
            catalog.extraWindows.map { catalog.stripSuffixes(it.id) to it.contextWindow }
        return rows.groupBy({ it.first }, { it.second }).mapValues { (_, windows) -> windows.max() }
    }
}
