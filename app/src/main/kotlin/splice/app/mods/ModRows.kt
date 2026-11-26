package splice.app.mods

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.app.mods.install.BUN_NOT_FOUND
import splice.app.mods.install.InstalledMod
import splice.app.mods.install.InstalledMods

/** The catalog carries the declaration unmodified, alongside current process state. */
internal class ModRows(private val mods: InstalledMods) {
    fun all(): JsonObject = buildJsonObject {
        putJsonArray("mods") { mods.all().sortedBy(InstalledMod::name).forEach { add(row(it)) } }
    }

    fun row(mod: InstalledMod): JsonObject {
        val bun = mods.bun.forRun(mod.bunPath)?.toString()
        val runtime = status(mod, bun)
        return buildJsonObject {
            mod.manifest.forEach { (key, value) -> put(key, value) }
            put("runtime", "ts")
            put("enabled", mod.enabled)
            put("linked", mod.linked)
            if (mod.linked) put("linkedPath", mod.root.toString())
            put("source", mod.source)
            put("hashChecked", !mod.linked)
            put("state", runtime.state.wire)
            put("failed", runtime.state == ModRuntimeState.FAILED)
            put("dropped", runtime.dropped)
            put("bunPath", mod.bunPath ?: bun)
            runtime.why?.let { put("why", it) }
            if (mod.linked) put("notice", "Linked files are not hash-checked.")
        }
    }

    private fun status(mod: InstalledMod, bun: String?): ModRuntime {
        val reported = mods.runtime.of(mod.name)
        return when {
            !mod.enabled -> ModRuntime(ModRuntimeState.DISABLED, dropped = reported?.dropped ?: 0)
            reported != null -> reported
            bun == null -> ModRuntime(ModRuntimeState.FAILED, BUN_NOT_FOUND)
            else -> ModRuntime(ModRuntimeState.STARTING)
        }
    }
}
