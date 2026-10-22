package splice.hub.holder

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The holder-only control frames never reach the MCP server once a front and holder have both negotiated this revision. */
object HolderProtocol {
    private const val PREFIX = "@splice-holder/1"
    private val controls = HolderControlFrames(PREFIX)
    private val wire = HolderWireIds()

    sealed interface Command {
        data class Recover(val round: String) : Command

        data class Acknowledge(val key: String) : Command

        data class Reverse(val key: String) : Command
    }

    sealed interface Event {
        data object Ready : Event

        data class State(val round: String, val key: String, val state: String) : Event

        data class Complete(val round: String) : Event

        data class Settled(val key: String) : Event

        data class Refused(val key: String) : Event
    }

    fun command(line: String): Command? = controls.command(line)

    fun event(line: String): Event? = controls.event(line)

    fun recover(round: String): String = "$PREFIX recover $round"

    fun acknowledge(key: String): String = "$PREFIX ack $key"

    fun reverse(key: String): String = "$PREFIX reverse $key"

    fun ready(): String = "$PREFIX ready"

    fun state(round: String, key: String, state: String): String = "$PREFIX state $round $key $state"

    fun complete(round: String): String = "$PREFIX complete $round"

    fun settled(key: String): String = "$PREFIX settled $key"

    fun refused(key: String): String = "$PREFIX refused $key"

    fun forward(line: String): String? = wire.forward(line)

    fun initialized(line: String): Boolean = wire.initialized(line)

    fun response(line: String): String? = wire.response(line)
}

private class HolderControlFrames(private val prefix: String) {
    fun command(line: String): HolderProtocol.Command? = parts(line)?.let(::commandFrame)

    fun event(line: String): HolderProtocol.Event? = parts(line)?.let(::eventFrame)

    private fun commandFrame(words: List<String>): HolderProtocol.Command? = when (words.firstOrNull()) {
        RECOVER -> oneArgument(words, ::round)?.let(HolderProtocol.Command::Recover)
        ACKNOWLEDGE -> oneArgument(words, ::key)?.let(HolderProtocol.Command::Acknowledge)
        REVERSE -> oneArgument(words, ::reverseKey)?.let(HolderProtocol.Command::Reverse)
        else -> null
    }

    private fun eventFrame(words: List<String>): HolderProtocol.Event? = when (words.firstOrNull()) {
        READY -> HolderProtocol.Event.Ready.takeIf { words.size == READY_WORDS }
        COMPLETE -> oneArgument(words, ::round)?.let(HolderProtocol.Event::Complete)
        SETTLED -> oneArgument(words, ::key)?.let(HolderProtocol.Event::Settled)
        REFUSED -> oneArgument(words, ::forwardKey)?.let(HolderProtocol.Event::Refused)
        STATE -> state(words)
        else -> null
    }

    private fun state(words: List<String>): HolderProtocol.Event.State? =
        words.takeIf { it.size == STATE_WORDS }?.let {
            val round = it[ROUND_INDEX].takeIf(::round)
            val key = it[KEY_INDEX].takeIf(::key)
            val state = it[STATE_INDEX].takeIf { value -> value in STATES }
            if (round == null || key == null) {
                null
            } else {
                state?.let { value -> HolderProtocol.Event.State(round, key, value) }
            }
        }

    private fun oneArgument(words: List<String>, accepts: (String) -> Boolean): String? =
        words.takeIf { it.size == COMMAND_WORDS }?.get(ARGUMENT_INDEX)?.takeIf(accepts)

    private fun parts(line: String): List<String>? =
        line.takeIf { it.length <= MAX_CONTROL_CHARS && it.startsWith("$prefix ") }
            ?.removePrefix("$prefix ")
            ?.split(' ')
            ?.takeUnless { it.any(String::isEmpty) }

    private fun round(value: String): Boolean = value.length == ROUND_CHARS && value.all(::hex)

    private fun hex(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f'

    private fun key(value: String): Boolean = forwardKey(value) || reverseKey(value)

    private fun forwardKey(value: String): Boolean = numbered(value, FORWARD_PREFIX)

    private fun reverseKey(value: String): Boolean = numbered(value, REVERSE_PREFIX)

    private fun numbered(value: String, prefix: String): Boolean =
        value.removePrefix(prefix).takeIf { value.startsWith(prefix) }?.all(Char::isDigit) == true
}

private class HolderWireIds {
    fun forward(line: String): String? = request(line)?.takeIf(::forwardKey)

    fun initialized(line: String): Boolean = method(line) == INITIALIZED

    fun response(line: String): String? = responseId(line)?.takeIf(::forwardKey)

    private fun request(line: String): String? = objectOrNull(line)
        ?.takeIf { method(it) != null }
        ?.get(ID)
        ?.let(::scalar)

    private fun responseId(line: String): String? = objectOrNull(line)
        ?.takeIf { method(it) == null }
        ?.get(ID)
        ?.let(::scalar)

    private fun method(line: String): String? = objectOrNull(line)?.let(::method)

    private fun method(value: JsonObject): String? = value[METHOD]?.let(::scalar)

    private fun scalar(value: JsonElement): String? = (value as? JsonPrimitive)?.contentOrNull

    private fun objectOrNull(line: String): JsonObject? =
        try {
            Json.parseToJsonElement(line) as? JsonObject
        } catch (_: SerializationException) {
            null
        }

    private fun forwardKey(value: String): Boolean =
        value.removePrefix(FORWARD_PREFIX).takeIf { value.startsWith(FORWARD_PREFIX) }?.all(Char::isDigit) == true
}

private const val RECOVER = "recover"
private const val ACKNOWLEDGE = "ack"
private const val REVERSE = "reverse"
private const val READY = "ready"
private const val COMPLETE = "complete"
private const val SETTLED = "settled"
private const val REFUSED = "refused"
private const val STATE = "state"
private const val INITIALIZED = "notifications/initialized"
private const val FORWARD_PREFIX = "f:"
private const val REVERSE_PREFIX = "s:"
private const val ID = "id"
private const val METHOD = "method"
private const val MAX_CONTROL_CHARS = 128
private const val ROUND_CHARS = 32
private const val READY_WORDS = 1
private const val COMMAND_WORDS = 2
private const val STATE_WORDS = 4
private const val ARGUMENT_INDEX = 1
private const val ROUND_INDEX = 1
private const val KEY_INDEX = 2
private const val STATE_INDEX = 3
private val STATES = setOf("pending", "armed", "queued", "settled")
