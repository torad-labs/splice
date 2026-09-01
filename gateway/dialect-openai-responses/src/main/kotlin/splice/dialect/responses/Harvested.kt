// NEW: the harvested text/thinking pair. Split from ResponsesHarvest.kt so the
// terminal-object readers are not billed for the DTO (concentration, 2026-08-19).
package splice.dialect.responses

public data class Harvested(val text: String, val thinking: String)
