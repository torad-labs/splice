// NEW: Oct 10, 2026 — the days a store keeps, asked at every use, split out of ActivityDays so a window the operator
// changes takes effect on the next read and the store's own file stays under the concentration ceiling.
package splice.core.storage

/** How many UTC days a store keeps, asked at every use so a window that changes takes effect on the next read. */
public fun interface RetentionDays {
    public fun days(): Int
}
