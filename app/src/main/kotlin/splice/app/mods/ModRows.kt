package splice.app.mods

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.app.mods.handover.MOD_HANDOVER_DROPPED
import splice.app.mods.install.BUN_NOT_FOUND
import splice.app.mods.install.InstalledMod
import splice.app.mods.install.InstalledMods

internal const val MOD_RUNTIME_STATE_FIELD = "state"

/** The catalog carries the declaration unmodified, alongside current process state. */
internal class ModRows(private val mods: InstalledMods) {
    fun all(): JsonObject = buildJsonObject {
        val dropped = mods.runtime.handoverDropped
        if (dropped > 0) put(MOD_HANDOVER_DROPPED, dropped)
        putJsonArray("mods") { mods.all().sortedBy(InstalledMod::name).forEach { add(row(it)) } }
    }

    fun row(mod: InstalledMod): JsonObject {
        val reported = mods.runtime.of(mod.name)
        val bun = reported?.diagnostics?.bunPath ?: mods.bun.forRun(mod.bunPath)?.toString()
        val runtime = status(mod, reported, bun)
        return buildJsonObject {
            mod.manifest.forEach { (key, value) -> put(key, value) }
            put("runtime", "ts")
            put("enabled", mod.enabled)
            put("linked", mod.linked)
            if (mod.linked) put("linkedPath", mod.root.toString())
            put("source", mod.source)
            put("hashChecked", !mod.linked)
            put(MOD_RUNTIME_STATE_FIELD, runtime.state.wire)
            put("failed", runtime.state == ModRuntimeState.FAILED)
            put("dropped", runtime.dropped)
            put("bunPath", bun ?: mod.bunPath)
            runtime.why?.let { put("why", it) }
            runtime.pid?.let { put("pid", it) }
            if (mod.linked) put("linkNotice", "Linked files are not hash-checked.")
            putDiagnostics(this, runtime)
        }
    }

    private fun putDiagnostics(out: JsonObjectBuilder, runtime: ModRuntime) = with(out) {
        val diagnostics = runtime.diagnostics
        put("droppedByEvent", JsonObject(diagnostics.droppedByEvent.mapValues { JsonPrimitive(it.value) }))
        put("droppedTransport", diagnostics.droppedTransport)
        put("droppedLogLines", diagnostics.droppedLogLines)
        put("restartsInWindow", diagnostics.restartsInWindow)
        diagnostics.lastError?.let { put("lastError", it) }
        runtime.loaded?.let { load ->
            put("notice", load.notice)
            put("bunVersion", load.bunVersion)
            putJsonArray("served") { load.served.sorted().forEach { add(it) } }
            putJsonArray("refused") { load.refused.forEach { add(it) } }
            putJsonArray("declaredNotServed") { load.declaredNotServed.forEach { add(it) } }
        }
    }

    private fun status(mod: InstalledMod, reported: ModRuntime?, bun: String?): ModRuntime = when {
        reported?.state == ModRuntimeState.FAILED && reported.pid != null -> reported
        !mod.enabled -> (reported ?: ModRuntime(ModRuntimeState.DISABLED))
            .copy(state = ModRuntimeState.DISABLED, why = null, pid = null)
        reported != null -> reported
        bun == null -> ModRuntime(ModRuntimeState.FAILED, BUN_NOT_FOUND)
        else -> ModRuntime(ModRuntimeState.STARTING)
    }
}
