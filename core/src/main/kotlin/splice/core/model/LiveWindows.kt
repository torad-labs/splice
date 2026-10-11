// NEW: V4-162 — the current catalog port; V4-440 also joins provider discovery refreshed at runtime.
package splice.core.model

/** An immutable catalog snapshot with the windows and discovered rows in force now.
 *
 *  The daemon joins accepted TOML window edits with its latest provider roster. The snapshot has no
 *  live source of its own, so delegation ends once. Null retains the caller's boot catalog.
 *  The historical name stays for source compatibility; :core owns neither file nor endpoint I/O. */
public fun interface LiveWindows {
    public fun current(): ModelCatalog?
}
