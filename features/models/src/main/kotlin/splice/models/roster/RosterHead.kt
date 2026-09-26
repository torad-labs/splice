// NEW: LAYOUT-01 — the head facts /api/models reads: the topology key and the model catalog whose
// live windows it reports. The control plane adapts its wider ManagedHead into this projection, so the
// models feature never depends upward on the control plane.
package splice.models.roster

import splice.core.model.ModelCatalog

/** One head as the models page sees it. [catalog] is read through `live()` per request, so holding
 *  the reference is enough for the windows the daemon re-reads (V4-162). */
public data class RosterHead(val key: String, val catalog: ModelCatalog?)
