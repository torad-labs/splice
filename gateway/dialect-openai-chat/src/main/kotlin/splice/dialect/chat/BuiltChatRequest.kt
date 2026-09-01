// NEW: (HD-24) the build() result type lifted out of ChatRequestBuilder.kt. It is the dialect's
// build RESULT (JsonObject + TurnMeta), not a wire type, so it does not belong in ChatRequest.kt
// (that would drag splice.core.turn into the closed-DTO file).
package splice.dialect.chat

import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnMeta

public data class BuiltChatRequest(val req: JsonObject, val meta: TurnMeta)
