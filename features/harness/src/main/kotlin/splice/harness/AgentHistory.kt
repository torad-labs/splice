// NEW: the agent's call (docs/specs/harness.md, decision 12, Kept and Beside the client's calls). A later request of a
// session gets back what the provider produced where the client holds one assistant message: the model's messages in
// its place, byte for byte, and a result the model's call beside the client's own is owed in the user message that
// carries the client's results, in the order the calls were made. A message no record matches goes as the client
// holds it, which is a shape the provider accepts.
package splice.harness

import kotlinx.serialization.json.JsonObject

/** One assistant message of a request that a kept call matches: its place among the messages, and the call. */
internal data class Match(val at: Int, val call: KeptCall)

internal class AgentHistory {
    private val wire = AgentWire()
    private val marks = CacheMarks()

    /** The assistant messages of [body] that [kept] matches, in order. One whose calls were made beside the client's
     *  is matched only where the next message answers a call of the client's: when it answers none (the person pressed
     *  Esc), the capability's call is left out with them. */
    fun matches(body: JsonObject, kept: Map<String, KeptCall>): List<Match> {
        if (kept.isEmpty()) return emptyList()
        val messages = wire.messages(body)
        return messages.indices.mapNotNull { at ->
            val message = messages[at]
            if (wire.role(message) != ASSISTANT) return@mapNotNull null
            val call = kept[marks.key(wire.content(message))]?.takeUnless { it.leftOut } ?: return@mapNotNull null
            Match(at, call).takeIf { call.beside.isEmpty() || answersClient(call, messages.getOrNull(at + 1)) }
        }
    }

    /** [body] with each of [matches] put back: the model's messages in place of the client's one, the client's cache
     *  mark moved to the last of them, and the results made beside the client's put in the next user message. */
    fun restored(body: JsonObject, matches: List<Match>): JsonObject {
        if (matches.isEmpty()) return body
        val at = matches.associateBy { it.at }
        val messages = wire.messages(body)
        val out = mutableListOf<JsonObject>()
        var owed: KeptCall? = null
        messages.forEachIndexed { index, message ->
            val match = at[index]
            when {
                match != null -> {
                    out += marked(match.call.messages, message)
                    owed = match.call.takeIf { it.beside.isNotEmpty() }
                }
                owed != null && wire.role(message) == USER -> {
                    out += beside(message, requireNotNull(owed))
                    owed = null
                }
                else -> out += message
            }
        }
        return wire.withMessages(body, out)
    }

    /** The ids of the calls the last of [call]'s messages made, in order. */
    private fun calls(call: KeptCall): List<String> =
        call.messages.lastOrNull()?.let(wire::content).orEmpty().filter(wire::isCall).mapNotNull(wire::callId)

    private fun answersClient(call: KeptCall, next: JsonObject?): Boolean {
        if (next == null || wire.role(next) != USER) return false
        val clients = calls(call).toSet() - call.beside.map { it.id }.toSet()
        return wire.content(next).any { wire.resultFor(it) in clients }
    }

    /** [replacing] with the client's cache mark on [client] moved to the last of them. */
    private fun marked(replacing: List<JsonObject>, client: JsonObject): List<JsonObject> {
        val mark = marks.mark(wire.content(client)) ?: return replacing
        return replacing.dropLast(1) + marks.marked(replacing.last(), mark)
    }

    /** [message] with the results made beside the client's, every result in the order the calls were made, and the
     *  client's other blocks after them as it sent them. */
    private fun beside(message: JsonObject, call: KeptCall): JsonObject {
        val blocks = wire.content(message)
        val theirs = call.beside.mapNotNull { result -> result.block?.let { result.id to it } }.toMap()
        val clients = blocks.filter { wire.resultFor(it) != null }.associateBy { requireNotNull(wire.resultFor(it)) }
        val order = calls(call)
        val results = order.mapNotNull { theirs[it] ?: clients[it] }
        val rest = blocks.filter { wire.resultFor(it) == null || wire.resultFor(it) !in order }
        return wire.message(USER, results + rest)
    }
}
