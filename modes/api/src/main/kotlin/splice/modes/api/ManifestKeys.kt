// NEW: a manifest table's keys read by type (ManifestCheck), each fault reported by its key, never by its value.
package splice.modes.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** [table]'s keys; [fault] takes each finding, and [prefix] is the table's path before a key (`heads.`). */
internal class ManifestKeys(
    private val table: JsonObject,
    private val fault: ManifestFault,
    private val prefix: String = "",
) {
    /** A finding for each key of this table outside [known]. */
    fun unknown(known: Set<String>) {
        table.keys.filter { it !in known }.forEach { fault.report("mode.toml has a key $prefix$it it does not take") }
    }

    /** A required string; null (and a finding) when it is missing or not a string. */
    fun string(key: String): String? {
        if (key !in table) return null.also { fault.report("$prefix$key is missing") }
        return optionalString(key)
    }

    fun optionalString(key: String): String? {
        val value = table[key] ?: return null
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: null.also { fault.report("$prefix$key is not a string") }
    }

    fun boolean(key: String): Boolean? {
        val value = table[key] ?: return null
        return (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: null.also { fault.report("$prefix$key is not true or false") }
    }

    /** A list of strings; null when absent (a finding when [required]) or not a list of strings. */
    fun strings(key: String, required: Boolean = true): List<String>? {
        val value = table[key] ?: return null.also { if (required) fault.report("$prefix$key is missing") }
        val list = (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (list == null || null in list) return null.also { fault.report("$prefix$key is not a list of strings") }
        return list.filterNotNull()
    }

    /** A sub-table as keys of its own, or null (a finding when it is there and not a table). */
    fun table(key: String, known: Set<String>): ManifestKeys? {
        val value = table[key] ?: return null
        val sub = value as? JsonObject ?: return null.also { fault.report("$prefix$key is not a table") }
        return ManifestKeys(sub, fault, "$prefix$key.").also { it.unknown(known) }
    }

    /** A sub-table as JSON, as written (a tool's or resource's `_meta`). */
    fun json(key: String): JsonObject? {
        val value = table[key] ?: return null
        return value as? JsonObject ?: null.also { fault.report("$prefix$key is not a table") }
    }
}
