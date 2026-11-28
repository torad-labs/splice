// NEW: one JSON object of a mod's files read key by key (ModManifestRead), each fault named by where it sits and by its
// key, never by its value.
package splice.app.mods

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** [obj]'s keys; [where] names the object in a finding ("mod.json", "pages entry 2"). */
internal class ModKeys(private val obj: JsonObject, private val faults: ModFaults, private val where: String) {
    /** A finding for each key outside [known]. */
    fun unknown(known: Set<String>) {
        obj.keys.filterNot { it in known }.forEach { faults.add("$where has a key $it it does not take") }
    }

    /** A required string: null, and a finding, when it is missing or not a string. */
    fun string(key: String): String? {
        if (key !in obj) return null.also { faults.add("$where has no $key") }
        return optionalString(key)
    }

    fun optionalString(key: String): String? {
        val value = obj[key] ?: return null
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: null.also { faults.add("$where's $key is not a string") }
    }

    /** A list of strings, empty when absent; a finding when it is there and is not one. */
    fun strings(key: String): List<String> {
        val value = obj[key] ?: return emptyList()
        val list = (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (list == null || null in list) {
            return emptyList<String>().also { faults.add("$where's $key is not a list of strings") }
        }
        return list.filterNotNull()
    }

    /** A list of objects, each with its 1-based place, empty when absent; a finding for the list or each entry that
     *  is not an object. */
    fun objects(key: String): List<Pair<Int, JsonObject>> {
        val value = obj[key] ?: return emptyList()
        val list = value as? JsonArray ?: return emptyList<Pair<Int, JsonObject>>().also {
            faults.add("$where's $key is not a list")
        }
        return list.mapIndexedNotNull { index, entry ->
            val place = index + 1
            (entry as? JsonObject)?.let { place to it }
                ?: null.also { faults.add("$key entry $place is not an object") }
        }
    }
}
