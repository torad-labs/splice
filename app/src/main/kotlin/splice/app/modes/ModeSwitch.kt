// NEW: PUT /api/modes/<name> (modes/README.md, "Turning a mode on and off"): `{on?, heads?: {<head>: ...}}`, where true
// or false sets a key, null clears it and a key left out stays as it is. It writes `[modes.<name>]` through the
// daemon's one splice.toml writer, and the next request of every session follows it. Nothing is written for a body it
// refuses, and no refusal quotes a value.
package splice.app.modes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.core.capability.CapabilityToml
import splice.core.mode.MODE_HEADS
import splice.core.mode.MODE_ON
import splice.core.mode.MODE_TABLES
import splice.core.mode.ModeSettingsReader
import splice.core.mode.ModeTableEdit
import splice.core.mode.ModeTableWrite
import splice.core.topology.TopologyWriteResult
import splice.core.topology.TopologyWriter
import splice.core.wire.HttpStatus
import splice.modes.api.FoundMode
import splice.modes.api.ModeFrom
import java.nio.file.Files

/** What a switch came to: the mode as it is after the write, or a refusal with its status and what is wrong. */
internal sealed class Switched {
    data class Done(val mode: FoundMode) : Switched()

    data class Refused(val status: Int, val error: String) : Switched()
}

internal class ModeSwitch(private val modes: DaemonModes) {
    fun put(name: String, body: JsonElement?, writer: TopologyWriter?): Switched {
        val mode = modes.mode(name) ?: return Switched.Refused(HttpStatus.NOT_FOUND, "splice carries no mode $name")
        val change = (body as? JsonObject)?.let { parse(mode, it) } ?: Change.Bad("the body is not {on?, heads?}")
        return when (change) {
            is Change.Bad -> Switched.Refused(HttpStatus.BAD_REQUEST, change.why)
            is Change.Set -> written(mode, change, writer)
        }
    }

    private fun written(mode: FoundMode, change: Change.Set, writer: TopologyWriter?): Switched {
        if (writer == null) return Switched.Refused(HttpStatus.SERVICE_UNAVAILABLE, "splice.toml cannot be written now")
        return when (val written = ModeTableWrite(writer).write(mode.name, change.edit())) {
            is TopologyWriteResult.Written -> {
                follow(mode.name, writer)
                Switched.Done(mode)
            }
            is TopologyWriteResult.Refused ->
                Switched.Refused(HttpStatus.CONFLICT, written.findings.joinToString("; ") { it.text() })
        }
    }

    /** The daemon follows mode [name]'s table as splice.toml holds it after the write, every key in it, an operator's
     *  edit since boot included; a table boot would refuse is followed as none, so it opens no route. */
    private fun follow(name: String, writer: TopologyWriter) {
        val tables = CapabilityToml.split(Files.readString(writer.path), MODE_TABLES).tables.filter { it.name == name }
        val now = ModeSettingsReader(modes.heads.toSet(), null).read(tables).settings[name]
        modes.follow(if (now == null) modes.settings - name else modes.settings + (name to now))
    }

    private fun parse(mode: FoundMode, body: JsonObject): Change {
        val unknown = body.keys.firstOrNull { it != MODE_ON && it != MODE_HEADS }
        val on = body[MODE_ON]?.let { flag(it) ?: Tri.Bad }
        val heads = heads(mode, body[MODE_HEADS])
        return when {
            unknown != null -> Change.Bad("$unknown is not a key a switch takes")
            on == Tri.Bad -> Change.Bad("$MODE_ON is not true, false or null")
            heads is Change.Bad -> heads
            else -> Change.Set(on, body.containsKey(MODE_ON), (heads as? Change.Set)?.heads.orEmpty())
        }
    }

    /** The `heads` of a body: each a declared head the mode can be offered on, with true, false or null. */
    private fun heads(mode: FoundMode, value: JsonElement?): Change {
        val each = when (value) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> value
            is JsonArray, is JsonPrimitive -> return Change.Bad(NOT_HEADS)
        }
        val read = LinkedHashMap<String, Tri>()
        val fault = each.entries.firstNotNullOfOrNull { (head, given) ->
            val tri = flag(given)
            headFault(mode, head, tri) ?: null.also { read[head] = checkNotNull(tri) }
        }
        return fault?.let { Change.Bad(it) } ?: Change.Set(null, false, read)
    }

    /** Why [head] cannot be switched to [tri] for [mode], or null when it can. */
    private fun headFault(mode: FoundMode, head: String, tri: Tri?): String? = when {
        head !in modes.heads -> "$head is not a head splice.toml declares"
        tri == null -> "$MODE_HEADS.$head is not true, false or null"
        modes.state(mode, head).from == ModeFrom.NEVER ->
            "mode ${mode.name} is never offered on $head: its manifest leaves the head out"
        else -> null
    }

    /** true or false, [Clear] for null, or null for anything else. */
    private fun flag(value: JsonElement): Tri? = when {
        value is JsonNull -> Tri.Clear
        value is JsonPrimitive && !value.isString -> value.booleanOrNull?.let { if (it) Tri.On else Tri.Off }
        else -> null
    }

    private enum class Tri(val value: Boolean?) {
        On(true),
        Off(false),
        Clear(null),
        Bad(null),
    }

    private sealed class Change {
        data class Bad(val why: String) : Change()

        data class Set(val on: Tri?, val onGiven: Boolean, val heads: Map<String, Tri>) : Change() {
            /** What this switch changes in the table splice.toml holds now, and nothing else. */
            fun edit(): ModeTableEdit = ModeTableEdit(onGiven, on?.value, heads.mapValues { it.value.value })
        }
    }
}

private const val NOT_HEADS = "$MODE_HEADS is not a table of head: true, false or null"
