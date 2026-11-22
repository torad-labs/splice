// NEW: one mode's folder checked whole (modes/README.md): its manifest key by key, every tool's two files, its
// resources, and its handler, with a finding for every fault at once, each naming the file or key at fault. No finding
// quotes a value except a name (a mode, a key, a file, a class).
package splice.modes.api

import splice.core.capability.TomlTree

private const val MODE_MANIFEST = "mode.toml"
private const val PROMPT = "prompt.md"

/** Checks the mode in [folder]; [handlers] are the classes the ServiceLoader made, by class name. */
internal class ManifestCheck(private val folder: ModeFolder, private val handlers: Map<String, Mode>) {
    private val faults = ModeFaults(folder.name)

    /** The mode when it is whole, else null; and every finding. */
    fun check(): Pair<FoundMode?, List<String>> {
        val toml = folder.read(MODE_MANIFEST)?.let(TomlTree::read)
        if (toml == null) {
            val why = if (folder.has(MODE_MANIFEST)) "is not valid TOML" else "is missing"
            faults.report("$MODE_MANIFEST $why")
            return null to faults.list
        }
        val manifest = ManifestRead(toml, folder.name, faults).manifest()
        val resources = ResourcesRead(folder, faults).resources(toml["resources"], manifest?.kind)
        val hooks = HooksRead(faults).hooks(toml["hooks"])
        val mode = manifest?.let { whole(it, resources, hooks) }
        return mode to faults.list
    }

    /** The mode of [manifest] once its tools, hooks and handler are read, or null when any finding was made. */
    private fun whole(manifest: ModeManifest, resources: List<ModeResource>, hooks: ModeHooksRead): FoundMode? {
        val tools = ToolsRead(folder, faults).tools(manifest.tools, manifest.server)
        val handler = handlers[manifest.handler]
        if (handler == null) faults.report("handler ${manifest.handler} is not a class splice could load for it")
        if (handler != null && hooks.hooks.any { it.worker == null }) {
            if (handler !is ModeHooks) {
                faults.report(
                    "a hook with no worker is answered in-process, and ${manifest.handler} is not a ModeHooks",
                )
            }
        }
        if (handler == null || faults.any()) return null
        return FoundMode(manifest, tools, resources, folder.read(PROMPT)?.trim(), handler, hooks.hooks, hooks.order)
    }
}
