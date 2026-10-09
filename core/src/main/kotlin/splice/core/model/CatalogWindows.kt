package splice.core.model

/** The context-window facts a [ModelCatalog] holds beside its picker rows and default window: window-only
 *  ids, ordered prefix rules, the head's own window, the live source and the provider's compaction reserve. */
public data class CatalogWindows(
    val extraWindows: List<ExtraWindow> = emptyList(),
    val windowRules: List<WindowRule> = emptyList(),
    /** The running head's current immutable catalog: accepted TOML windows and refreshed discovery.
     *  Roster and window methods delegate through it; field readers resolve [ModelCatalog.live] once per operation.
     *  Null keeps this snapshot fixed, as for catalogs built outside daemon head assembly. */
    val liveWindows: LiveWindows? = null,
    /** The head's own window (its `context_window`, or the contextWindowOverride knob) when it replaced
     *  the provider's windows on every entry, rule and the default; null = the provider's numbers stand.
     *  It exists so a reader can say WHERE a window came from (console review 2026-09-24: /api/models
     *  labelled a head's 300k "model"). */
    val headWindow: Long? = null,
    /** Provider-supplied empirical reserve; absent preserves the existing window-ratio behavior. */
    val compactionReserveDefaults: CompactionReserveDefaults? = null,
)
