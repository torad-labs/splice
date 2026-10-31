// NEW: preserve user hook order across global and head-local settings, replacing only exact owned commands.
package splice.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import splice.client.login.LoginInterception
import splice.client.resume.ForegroundHook
import splice.client.resume.ResumeHook
import splice.core.util.JsonScalars
import java.nio.file.Path

internal object HookSettings {
    const val ORIGINS = ".splice-hook-origins.json"

    data class Merged(val hooks: JsonObject?, val inherited: JsonObject?)

    /** Local edits do not erase evidence of an interrupted swap. When deliberate copies contain both
     * inherited snapshots, prefer the completed snapshot and preserve the old occurrences as local hooks. */
    fun inherited(memo: JsonObject, local: JsonElement?): JsonElement? {
        val hooks = local ?: JsonNull
        val before = memo["before_inherited"]
        val after = memo["after_inherited"]
        return when {
            memo["before_hooks"] == hooks -> before
            memo["after_hooks"] == hooks -> after
            hasDistinctive(hooks, after, before) -> after
            hasDistinctive(hooks, before, after) -> before
            else -> after
        }
    }

    private fun hasDistinctive(local: JsonElement, side: JsonElement?, other: JsonElement?): Boolean {
        val current = local as? JsonObject ?: return false
        val expected = side as? JsonObject ?: return false
        val prior = other as? JsonObject
        return expected.any { (event, value) ->
            val shared = (prior?.get(event) as? JsonArray).orEmpty()
            val distinct = subtract((value as? JsonArray).orEmpty(), shared)
            val available = subtract((current[event] as? JsonArray).orEmpty(), shared)
            distinct.any(available::contains)
        }
    }

    fun origins(
        previous: JsonElement?,
        local: JsonElement?,
        current: JsonElement?,
        result: JsonElement?,
    ): JsonObject = JsonObject(
        mapOf(
            "before_hooks" to (local ?: JsonNull),
            "before_inherited" to (previous ?: JsonNull),
            "after_hooks" to (result ?: JsonNull),
            "after_inherited" to (current ?: JsonNull),
        ),
    )

    fun merge(
        global: JsonElement?,
        local: JsonElement?,
        additions: Map<String, List<JsonObject>>,
        configDir: Path,
        inheritedBefore: JsonElement? = null,
    ): Merged {
        val owned = LoginInterception.ownedHookCommands(configDir) + setOf(
            configDir.resolve(ResumeHook.RESUME_HOOK_SH).toString(),
            configDir.resolve(ForegroundHook.SCRIPT).toString(),
        )
        val localEvents = local as? JsonObject
        val globalEvents = global as? JsonObject
        val events = localEvents?.keys.orEmpty() + globalEvents?.keys.orEmpty() + additions.keys
        if (events.isEmpty()) return Merged(null, null)
        val nextInherited = LinkedHashMap<String, JsonElement>()
        val hooks = JsonObject(
            events.associateWith { event ->
                val prior = (inheritedBefore as? JsonObject)?.get(event)
                val users = subtract(userEntries(localEvents?.get(event), owned), userEntries(prior, owned))
                // The local file can already carry inherited entries. Match occurrences, not a set:
                // intentional duplicates and every surviving local entry retain their exact order.
                val inherited = users.toMutableList()
                val fresh = userEntries(globalEvents?.get(event), owned).filter { entry ->
                    val index = inherited.indexOf(entry)
                    if (index < 0) {
                        true
                    } else {
                        inherited.removeAt(index)
                        false
                    }
                }
                nextInherited[event] = JsonArray(fresh)
                JsonArray(users + fresh + additions[event].orEmpty())
            },
        )
        return Merged(hooks, JsonObject(nextInherited))
    }

    /** Remove only the known inherited occurrences. Unknown origins are always user-owned. */
    private fun subtract(local: List<JsonElement>, inherited: List<JsonElement>): List<JsonElement> {
        val remaining = inherited.toMutableList()
        return local.filter { entry ->
            val index = remaining.indexOf(entry)
            if (index < 0) {
                true
            } else {
                remaining.removeAt(index)
                false
            }
        }
    }

    private fun userEntries(value: JsonElement?, owned: Set<String>): List<JsonElement> =
        (value as? JsonArray).orEmpty().mapNotNull { entry ->
            val obj = entry as? JsonObject
            val hooks = obj?.get("hooks") as? JsonArray
            if (obj == null || hooks == null) return@mapNotNull entry
            val users = hooks.filterNot { hook ->
                (hook as? JsonObject)?.let { JsonScalars.str(it, "command") } in owned
            }
            when {
                users.isEmpty() -> null
                users.size == hooks.size -> entry
                else -> JsonObject(obj + ("hooks" to JsonArray(users)))
            }
        }
}
