// NEW: muse-tool-cache (2026-11-16, docs/specs/muse-tool-cache.md, decision 4). The model calls a tool that arrived
// in the middle of a conversation through the caller (ToolCaller). Before the round's fold reads the backend's
// stream, each call of the caller becomes a call of the tool it names, under that tool's own name and with its
// arguments. So everything after this point, Claude Code included, sees the call the model meant and never the caller.
package splice.dialect.responses.stream

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.dialect.responses.tools.NamedCall
import splice.dialect.responses.tools.TOOL_CALLER_NAME
import splice.dialect.responses.tools.ToolCaller
import splice.upstream.StreamTranslator
import splice.upstream.sse.WireSink

/** A round's translator, reading the backend's stream with the caller's calls already turned into the calls they
 *  name. */
internal class ResponsesCallerTranslator(private val round: StreamTranslator) : StreamTranslator {
    override fun prepareSink(sink: WireSink) = round.prepareSink(sink)

    override suspend fun driveTurn(upstream: Flow<JsonObject>, sink: WireSink): TurnOutcome {
        val calls = ResponsesCallerCalls()
        return round.driveTurn(upstream.transform { event -> calls.asMeant(event).forEach { emit(it) } }, sink)
    }
}

/**
 * One round's calls of the caller. The name of the tool a call is for sits inside its arguments, and Claude Code is
 * told a call's name before its arguments, so a call of the caller is held until its arguments are whole and then
 * goes on as one call of the named tool.
 *
 * A call that names no tool (malformed arguments, or none by the time the round ends) goes on exactly as the backend
 * sent it: there is no tool to hand it to, the fold judges it as it judges any call, and Claude Code refuses a tool it
 * does not have, which tells the model what it did.
 */
internal class ResponsesCallerCalls(private val caller: ToolCaller = ToolCaller()) {

    private val frames = ResponsesFrameParse()

    /** The calls of the caller the backend has not finished sending, by output index. */
    private val held = hashMapOf<Int, HeldCall>()

    /** [event] as the model meant it: nothing while a call of the caller is still arriving, that call as a call of
     *  the tool it names once it is whole, and any other event as it came. */
    fun asMeant(event: JsonObject): List<JsonObject> {
        val type = JsonScalars.strOrEmpty(event[FIELD_TYPE])
        val at = frames.intOr(event[OUTPUT_INDEX])
        val call = at?.let(held::get)
        return when {
            type in ROUND_ENDS -> unfinished() + event
            at == null -> listOf(event)
            type == ITEM_ADDED && callsCaller(event) -> hold(at, event)
            call == null -> listOf(event)
            else -> next(at, call, type, event)
        }
    }

    private fun callsCaller(event: JsonObject): Boolean {
        val item = event[FIELD_ITEM] as? JsonObject
        val call = JsonScalars.str(item, FIELD_TYPE) == FUNCTION_CALL
        return call && JsonScalars.str(item, FIELD_NAME) == TOOL_CALLER_NAME
    }

    private fun hold(at: Int, event: JsonObject): List<JsonObject> {
        held[at] = HeldCall(event)
        return emptyList()
    }

    /** The next event of the held call [call]. */
    private fun next(at: Int, call: HeldCall, type: String, event: JsonObject): List<JsonObject> = when (type) {
        ARGUMENTS_DELTA -> call.arrive(event)
        ARGUMENTS_DONE -> whole(at, call, event, JsonScalars.str(event, FIELD_ARGUMENTS))
        ITEM_DONE -> whole(at, call, event, JsonScalars.str(event[FIELD_ITEM] as? JsonObject, FIELD_ARGUMENTS))
            .also { held.remove(at) }
        else -> listOf(event)
    }

    /** [event] ends [call]'s arguments, or the call itself, and states the arguments as [stated] unless the deltas
     *  alone carried them. The call goes on as the named tool's from here, opened the first time it is whole. */
    private fun whole(at: Int, call: HeldCall, event: JsonObject, stated: String?): List<JsonObject> {
        val opened = call.named
        val named = opened ?: caller.named(stated?.takeIf { it.isNotEmpty() } ?: call.arguments.toString())
        return when {
            named == null -> {
                held.remove(at)
                call.sent + event
            }
            opened == null -> {
                call.named = named
                val opening = JsonObject(call.added + (FIELD_ITEM to item(call.added, named, arguments = "")))
                listOf(opening, ended(event, named))
            }
            else -> listOf(ended(event, named))
        }
    }

    /** The calls of the caller the round ended on before they were whole, as the backend sent them. */
    private fun unfinished(): List<JsonObject> {
        val rest = held.entries.sortedBy { it.key }.map { it.value }.filter { it.named == null }.flatMap { it.sent }
        held.clear()
        return rest
    }

    /** [event], the end of a call's arguments or of the call itself, as the end of [named]'s. */
    private fun ended(event: JsonObject, named: NamedCall): JsonObject = if (event[FIELD_ITEM] is JsonObject) {
        JsonObject(event + (FIELD_ITEM to item(event, named, named.arguments)))
    } else {
        JsonObject(
            event + (FIELD_NAME to JsonPrimitive(named.tool)) + (FIELD_ARGUMENTS to JsonPrimitive(named.arguments)),
        )
    }

    /** The call item [event] carries, as a call of [named]'s tool with [arguments]. */
    private fun item(event: JsonObject, named: NamedCall, arguments: String): JsonObject = JsonObject(
        (event[FIELD_ITEM] as? JsonObject).orEmpty() +
            (FIELD_NAME to JsonPrimitive(named.tool)) +
            (FIELD_ARGUMENTS to JsonPrimitive(arguments)),
    )
}

/** One call of the caller: the event that opened it, everything the backend has sent of it, its arguments so far, and
 *  the call it turned out to name once it went on as that tool's. */
private class HeldCall(val added: JsonObject) {
    val sent = mutableListOf(added)
    val arguments = StringBuilder()
    var named: NamedCall? = null

    /** Takes one delta of the call's arguments, which goes on to Claude Code only once the call is whole. */
    fun arrive(delta: JsonObject): List<JsonObject> {
        sent.add(delta)
        arguments.append(JsonScalars.strOrEmpty(delta[DELTA]))
        return emptyList()
    }
}

private const val FIELD_TYPE = "type"
private const val FIELD_ITEM = "item"
private const val FIELD_NAME = "name"
private const val FIELD_ARGUMENTS = "arguments"
private const val FUNCTION_CALL = "function_call"
private const val ITEM_ADDED = "response.output_item.added"
private const val ITEM_DONE = "response.output_item.done"
private const val ARGUMENTS_DELTA = "response.function_call_arguments.delta"
private const val ARGUMENTS_DONE = "response.function_call_arguments.done"

/** The events a round ends on: a call of the caller still held then will never be whole. */
private val ROUND_ENDS =
    setOf("response.completed", "response.done", "response.incomplete", "response.failed", "response.error", "error")
