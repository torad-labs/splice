// NEW: the values of one capability table read as splice.toml spells them, each wrong one a finding
// (CapabilityDeclarations). A finding quotes the key at fault and never the value written, but for a name.
package splice.core.capability

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import splice.core.topology.TopologyFinding

private val SAFE = Regex("[A-Za-z0-9_.-]{1,64}")

/** [table]'s values, and the [findings] reading them has made. */
internal class CapabilityValues(val table: CapabilityTable) {
    val findings = mutableListOf<TopologyFinding>()

    /** A finding at [key] ("" for the table itself, `when.turn` inside one), on the line the key was written. */
    fun fault(key: String, message: String) {
        val path = if (key.isEmpty()) "capabilities.${table.name}" else "capabilities.${table.name}.$key"
        findings += TopologyFinding(path, message, table.lines[key.substringBefore('.')] ?: table.line)
    }

    fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun strings(value: JsonElement): List<String>? = (value as? JsonArray)?.map { string(it) ?: return null }

    /** The string at [key]; absent is null, and a value that is not a string is a finding. */
    fun text(key: String): String? {
        val value = table.keys[key] ?: return null
        return string(value) ?: null.also { fault(key, "must be a string") }
    }

    /** The list of strings at [key]; absent is null, and anything else is a finding that it must be [what]. */
    fun names(key: String, what: String): List<String>? {
        val value = table.keys[key] ?: return null
        return strings(value) ?: null.also { fault(key, "must be $what") }
    }

    /** [value] as one of [options], by its spelling; absent is [default]. The finding names the key and the values it
     *  takes, never the value written. */
    fun <T> choice(key: String, value: JsonElement?, options: Map<String, T>, default: T): T? {
        if (value == null) return default
        return options[string(value)] ?: null.also { fault(key, "must be one of ${options.keys.joinToString()}") }
    }

    /** A name as a finding may quote it: one that reads as a name, else nothing of it. */
    fun safe(name: String): String = name.takeIf { SAFE.matches(it) } ?: "(not a name)"
}
