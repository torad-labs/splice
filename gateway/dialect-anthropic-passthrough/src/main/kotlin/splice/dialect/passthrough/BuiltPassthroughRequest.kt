// NEW: the builder's published return shape — split out of PassthroughRequestBuilder.kt
// (2026-08-17, concentration campaign). Not part of the building itself; its only consumer
// destructures it one line later. Kept identical name and shape.
package splice.dialect.passthrough

import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnMeta

public data class BuiltPassthroughRequest(val req: JsonObject, val meta: TurnMeta)
