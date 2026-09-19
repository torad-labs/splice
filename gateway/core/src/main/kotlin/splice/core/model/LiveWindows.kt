// NEW: V4-162 — the port a RUNNING head's catalog reads its context windows through, so a
// context_window edit in splice.toml reaches the daemon without a restart.
package splice.core.model

/** Where a running head's [ModelCatalog] reads the windows in force NOW.
 *
 *  The daemon's implementation (app TopologyWindows) re-reads splice.toml when its modification
 *  time moves and answers with this head's catalog carrying the windows the file declares: the same
 *  roster with only the numbers changed ([ModelCatalog.withWindowsOf]). Null means nothing newer
 *  than boot, and the catalog keeps the windows it was built with. :core never reads the file (the
 *  TOML parser is an :app dependency by module law), which is why this is a port and not a path. */
public fun interface LiveWindows {
    public fun current(): ModelCatalog?
}
