// NEW: V4-444 metadata-only trace-list selection, separate from full-record body hydration.
package splice.head.trace.body

/** Full records hydrate bodies; summaries validate references without constructing body strings. */
internal enum class TraceBodySelection { RECORDS, SUMMARY }
