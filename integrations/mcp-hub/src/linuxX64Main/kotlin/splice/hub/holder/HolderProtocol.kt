package splice.hub.holder

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Parses holder control and identifies the child messages that own settlement. */
object HolderProtocol {
    private val controls = HolderControlFrames(CONTROL_PREFIX)
    private val wire = HolderWireIds()

    sealed interface Command {
        data class Recover(val round: String) : Command

        data class Acknowledge(val key: String) : Command

        data class Reverse(val key: String, val identity: String) : Command
    }

    sealed interface Event {
        data object Ready : Event

        data class Armed(val key: String) : Event

        data class State(val round: String, val key: String, val state: String) : Event

        data class Complete(val round: String) : Event

        data class Settled(val key: String) : Event

        data class Refused(val key: String) : Event
    }

    fun command(line: String): Command? = controls.command(line)

    fun event(line: String): Event? = controls.event(line)

    fun controlSocket(socket: String): String = "$socket.control"

    fun forward(line: String): String? = wire.forward(line)

    fun initialized(line: String): Boolean = wire.initialized(line)

    fun response(line: String): String? = wire.response(line)

    fun reverseResponse(line: String, identity: String): Boolean = wire.reverseResponse(line, identity)
}

/** Writes holder-only frames for the private control socket. */
object HolderControlWire {
    fun recover(round: String): String = "$CONTROL_PREFIX recover $round"

    fun acknowledge(key: String): String = "$CONTROL_PREFIX ack $key"

    fun reverse(key: String, id: JsonElement): String =
        "$CONTROL_PREFIX reverse $key ${HolderWireIds().identity(id)}"

    fun ready(): String = "$CONTROL_PREFIX ready"

    fun armed(key: String): String = "$CONTROL_PREFIX armed $key"

    fun state(round: String, key: String, state: String): String = "$CONTROL_PREFIX state $round $key $state"

    fun complete(round: String): String = "$CONTROL_PREFIX complete $round"

    fun settled(key: String): String = "$CONTROL_PREFIX settled $key"

    fun refused(key: String): String = "$CONTROL_PREFIX refused $key"
}

private class HolderControlFrames(private val prefix: String) {
    fun command(line: String): HolderProtocol.Command? = parts(line)?.let(::commandFrame)

    fun event(line: String): HolderProtocol.Event? = parts(line)?.let(::eventFrame)

    private fun commandFrame(words: List<String>): HolderProtocol.Command? = when (words.firstOrNull()) {
        RECOVER -> oneArgument(words, ::round)?.let(HolderProtocol.Command::Recover)
        ACKNOWLEDGE -> oneArgument(words, ::key)?.let(HolderProtocol.Command::Acknowledge)
        REVERSE -> reverse(words)
        else -> null
    }

    private fun eventFrame(words: List<String>): HolderProtocol.Event? = when (words.firstOrNull()) {
        READY -> HolderProtocol.Event.Ready.takeIf { words.size == READY_WORDS }
        ARMED -> oneArgument(words, ::reverseKey)?.let(HolderProtocol.Event::Armed)
        COMPLETE -> oneArgument(words, ::round)?.let(HolderProtocol.Event::Complete)
        SETTLED -> oneArgument(words, ::key)?.let(HolderProtocol.Event::Settled)
        REFUSED -> oneArgument(words, ::forwardKey)?.let(HolderProtocol.Event::Refused)
        STATE -> state(words)
        else -> null
    }

    private fun reverse(words: List<String>): HolderProtocol.Command.Reverse? =
        words.takeIf { it.size == REVERSE_WORDS }?.let {
            val key = it[ARGUMENT_INDEX].takeIf(::reverseKey)
            val identity = it[IDENTITY_INDEX].takeIf(::identity)
            if (key == null || identity == null) null else HolderProtocol.Command.Reverse(key, identity)
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

    private fun identity(value: String): Boolean = value.length % HEX_DIGITS == 0 && value.all(::hex)

    private fun hex(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f'

    private fun forwardKey(value: String): Boolean = numbered(value, FORWARD_PREFIX)

    private fun reverseKey(value: String): Boolean = numbered(value, REVERSE_PREFIX)

    private fun numbered(value: String, prefix: String): Boolean =
        value.removePrefix(prefix).takeIf { value.startsWith(prefix) }?.all(Char::isDigit) == true
}

private class HolderWireIds {
    fun forward(line: String): String? = request(line)?.takeIf(::forwardKey)

    fun initialized(line: String): Boolean = method(line) == INITIALIZED

    fun response(line: String): String? = responseId(line)?.takeIf(::scalar)?.takeIf(::forwardKey)

    fun reverseResponse(line: String, identity: String): Boolean = responseId(line)?.let(::identity) == identity

    fun identity(id: JsonElement): String = Json.encodeToString(JsonElement.serializer(), id)
        .encodeToByteArray()
        .joinToString("") { byte -> byte.toUByte().toString(HEX).padStart(HEX_DIGITS, '0') }

    private fun request(line: String): String? = objectOrNull(line)
        ?.takeIf { method(it) != null }
        ?.get(ID)
        ?.let(::scalar)

    private fun responseId(line: String): JsonElement? = objectOrNull(line)
        ?.takeIf(::response)
        ?.get(ID)

    private fun response(value: JsonObject): Boolean {
        if (method(value) != null) return false
        return RESULT in value || ERROR in value
    }

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

private const val CONTROL_PREFIX = "@splice-holder/1"
private const val RECOVER = "recover"
private const val ACKNOWLEDGE = "ack"
private const val REVERSE = "reverse"
private const val READY = "ready"
private const val ARMED = "armed"
private const val COMPLETE = "complete"
private const val SETTLED = "settled"
private const val REFUSED = "refused"
private const val STATE = "state"
private const val INITIALIZED = "notifications/initialized"
private const val FORWARD_PREFIX = "f:"
private const val REVERSE_PREFIX = "s:"
private const val ID = "id"
private const val METHOD = "method"
private const val RESULT = "result"
private const val ERROR = "error"
private const val MAX_CONTROL_CHARS = 1024 * 1024
private const val ROUND_CHARS = 32
private const val READY_WORDS = 1
private const val COMMAND_WORDS = 2
private const val REVERSE_WORDS = 3
private const val STATE_WORDS = 4
private const val ARGUMENT_INDEX = 1
private const val IDENTITY_INDEX = 2
private const val ROUND_INDEX = 1
private const val KEY_INDEX = 2
private const val STATE_INDEX = 3
private const val HEX_DIGITS = 2
private const val HEX = 16
private val STATES = setOf("pending", "armed", "queued", "settled")
