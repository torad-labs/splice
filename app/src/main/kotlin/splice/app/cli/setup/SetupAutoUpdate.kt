// NEW: `splice setup`'s automatic-updates question: `[daemon] auto_update`, which lets the daemon install a newer
// release itself (UpgradeAuto) on an install that install.sh laid. Asked only on an interactive setup, and only while
// splice.toml has never said it: an answer already written is the person's, and setup does not ask it again. A no
// writes nothing, since an absent auto_update already reads as off.
package splice.app.cli.setup

import splice.core.config.StatePaths
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriteResult
import splice.core.topology.TopologyWriter
import splice.core.util.Cancellables
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

internal class SetupAutoUpdate(private val prompts: SetupPrompts) {
    /** True when the person turned automatic updates on; false with no console or where the file already answers. */
    fun offer(path: Path): Boolean =
        prompts.hasConsole() && held(path) == null && prompts.frame.confirm(QUESTION, false)

    fun summary(on: Boolean): List<String> =
        if (on) listOf("Automatic updates: on ([daemon] auto_update = true)") else emptyList()

    /** Writes `auto_update = true` under `[daemon]` when [on], changing nothing else in [path]. */
    fun save(path: Path, on: Boolean) {
        if (!on) return
        val result = Cancellables.runCatchingBestEffort {
            val current = TopologyLoader.parse(Files.readString(path))
            val writer = TopologyWriter(path, StatePaths().configBackupsDir, TopologyParse(TopologyLoader::parse))
            writer.write(current.copy(daemon = current.daemon.copy(autoUpdate = true)), writer.tree(current))
        }
        val refused = result.getOrNull() as? TopologyWriteResult.Refused
        val why = refused?.findings?.joinToString("; ") { it.text() } ?: result.exceptionOrNull()?.message
        why?.let {
            System.err.println("splice: automatic updates were not turned on ($it); set [daemon] auto_update = true")
        }
    }

    private fun held(path: Path): Boolean? = Cancellables.runCatchingBestEffort {
        if (Files.exists(path)) TopologyLoader.parse(Files.readString(path)).daemon.autoUpdate else null
    }.getOrNull()
}

private const val QUESTION = "Install new splice releases automatically? The daemon installs each one itself."
