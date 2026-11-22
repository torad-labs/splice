// NEW: a mode's `[hooks.<moment>]` entries (modes/README.md, "Hooks"): each moment's handler, its time limit, what a
// failure does, and whether the handler answers it in-process or a worker does out of process.
package splice.modes.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

private val HOOK_KEYS = setOf("order") + ModeHookMoment.entries.map { it.wire }
private val MOMENT_KEYS = setOf("timeout_s", "on_failure", "worker")
private val WORKER_KEYS = setOf("kind", "url", "auth_env", "server", "tool")

private const val HOOK_TIMEOUT_S = 30L
private const val HOOK_TIMEOUT_MAX_S = 3600L

/** A mode's hooks as its manifest declares them, and the order they run in among the other modes'. */
internal data class ModeHooksRead(val hooks: List<ModeHook> = emptyList(), val order: Int = 0)

/** Reads `hooks` in mode.toml; [fault] hears every fault, named by its key. */
internal class HooksRead(private val fault: ModeFaults) {
    fun hooks(element: JsonElement?): ModeHooksRead {
        val table = element as? JsonObject ?: return ModeHooksRead().also {
            if (element != null) fault.report("hooks is not a table")
        }
        val keys = ManifestKeys(table, fault, "hooks.")
        keys.unknown(HOOK_KEYS)
        val order = order(table)
        val hooks = ModeHookMoment.entries.mapNotNull { moment ->
            val value = table[moment.wire] ?: return@mapNotNull null
            val hook = value as? JsonObject
                ?: return@mapNotNull null.also { fault.report("hooks.${moment.wire} is not a table") }
            moment(moment, hook)
        }
        return ModeHooksRead(hooks, order)
    }

    private fun order(table: JsonObject): Int {
        val value = table["order"] ?: return 0
        return (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: 0.also { fault.report("hooks.order is not an integer") }
    }

    private fun moment(moment: ModeHookMoment, table: JsonObject): ModeHook? {
        val before = fault.size
        val keys = ManifestKeys(table, fault, "hooks.${moment.wire}.")
        keys.unknown(MOMENT_KEYS)
        val timeout = timeout(moment, table)
        val closed = failure(moment, table)
        val worker = keys.table("worker", WORKER_KEYS)?.let { worker(moment, it, table["worker"] as JsonObject) }
        if (fault.size > before) return null
        return ModeHook(moment, timeout, closed, worker)
    }

    private fun timeout(moment: ModeHookMoment, table: JsonObject): Long {
        val value = table["timeout_s"] ?: return HOOK_TIMEOUT_S
        val seconds = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
        if (seconds == null || seconds !in 1..HOOK_TIMEOUT_MAX_S) {
            fault.report("hooks.${moment.wire}.timeout_s is not 1 to $HOOK_TIMEOUT_MAX_S")
        }
        return seconds ?: HOOK_TIMEOUT_S
    }

    /** Whether a hook that errs or runs out of time refuses the turn. Absent or `open`: it goes on. */
    private fun failure(moment: ModeHookMoment, table: JsonObject): Boolean {
        val word = (table["on_failure"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        return when (word) {
            "open" -> false
            "closed" -> true
            else -> false.also { fault.report("hooks.${moment.wire}.on_failure is neither open nor closed") }
        }
    }

    private fun worker(moment: ModeHookMoment, keys: ManifestKeys, table: JsonObject): ModeHookWorker? =
        when (val kind = keys.string("kind")) {
            "http" -> httpWorker(moment, keys, table)
            "mcp" -> mcpWorker(moment, keys, table)
            null -> null
            else -> null.also { fault.report("hooks.${moment.wire}.worker.kind is neither http nor mcp (it is $kind)") }
        }

    private fun httpWorker(moment: ModeHookMoment, keys: ManifestKeys, table: JsonObject): ModeHookWorker? {
        wrongKeys(moment, table, setOf("kind", "url", "auth_env"))
        val url = keys.string("url") ?: return null
        return ModeHookWorker.Http(url, keys.optionalString("auth_env"))
    }

    private fun mcpWorker(moment: ModeHookMoment, keys: ManifestKeys, table: JsonObject): ModeHookWorker? {
        wrongKeys(moment, table, setOf("kind", "server", "tool"))
        val server = keys.string("server") ?: return null
        val tool = keys.string("tool") ?: return null
        return ModeHookWorker.Mcp(server, tool)
    }

    /** A worker carrying a key its kind does not take is a finding, not silently dropped. */
    private fun wrongKeys(moment: ModeHookMoment, table: JsonObject, kindKeys: Set<String>) {
        (WORKER_KEYS - kindKeys).filter { it in table }.forEach {
            fault.report("hooks.${moment.wire}.worker.$it is not a key this worker kind takes")
        }
    }
}
