package splice.provider.codex.state.replay

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay

/** Projection attaches only validated client declaration pairs and preceding reasoning to a callback.
 * The declaration is callback bookkeeping; the reasoning still requires its own captured witness. */
internal object CodeModeCallbackReplay {
    fun native(input: ResponsesCodeModeInput, owned: Set<String>): ResponsesCodeModeInput = input.copy(
        replayItems = input.replayItems.map { native(it, owned) }.filter { it.items.isNotEmpty() },
    )

    fun native(replay: ResponsesCodeModeReplay, owned: Set<String>): ResponsesCodeModeReplay =
        if (replay.callbackId in owned) {
            replay.copy(
                items = replay.items.filterNot { JsonScalars.str(it as? JsonObject, "type") in DECLARATION_TYPES },
            )
        } else {
            replay
        }
}

// The projection validates the matching client-executed declaration pair before assigning callback affinity.
private val DECLARATION_TYPES = setOf("tool_search_call", "tool_search_output")
