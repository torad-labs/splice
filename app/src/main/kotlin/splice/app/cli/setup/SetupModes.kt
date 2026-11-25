// NEW: `splice setup`'s Modes step (modes/README.md): a line on what modes are and which this splice carries, on or off
// as splice.toml and each mode's manifest decide (a mode on for some callers alone, as its manifest's `on_for` leaves
// it, is on and says for whom), then one yes-or-no for each mode that is off. Only an interactive
// setup asks, and only a yes writes: a mode turned on gets `on = true` in its `[modes.<name>]` table, written through
// the same table writer the console's switch uses (ModeTableWrite), every other line of splice.toml kept. A mode
// already on is left as it is; the console's Modes page turns one off.
package splice.app.cli.setup

import splice.app.modes.ModeBoot
import splice.core.mode.ModeTableEdit
import splice.core.mode.ModeTableWrite
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriteResult
import splice.core.topology.TopologyWriter
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.modes.api.ModeCaller
import splice.modes.api.ModeRule
import splice.topology.TopologyLoader
import splice.topology.TopologyStatePaths
import java.nio.file.Files
import java.nio.file.Path

/** One mode as the step shows it: its [name], its [summary], whether it is [on] now, and, when it is on for some
 *  callers alone, those callers ([onlyFor]; empty when it is on or off for every one). */
internal data class ModeRow(
    val name: String,
    val summary: String,
    val on: Boolean,
    val onlyFor: List<ModeCaller> = emptyList(),
) {
    /** How the step's list says it: `on`, `off`, or `on for Claude Desktop`. */
    val said: String get() = when {
        onlyFor.isNotEmpty() -> "on for " + onlyFor.joinToString(", ") { CALLER_WORDS.getValue(it) }
        on -> "on"
        else -> "off"
    }
}

/** The modes this splice carries, as [path]'s splice.toml and each manifest decide. */
internal fun interface ModeRows {
    operator fun invoke(path: Path): List<ModeRow>
}

/** Every mode the jar carries, on as `[modes.<name>] on` says, else as its manifest does (ModeRule). */
internal class CarriedModes : ModeRows {
    override fun invoke(path: Path): List<ModeRow> {
        val tables = if (Files.exists(path)) TopologyLoader.parse(Files.readString(path)).modes else emptyMap()
        return ModeBoot().catalog.modes.filterNot { it.name in MODE_TEMPLATES }.map { mode ->
            val setting = tables[mode.name]
            val every = ModeRule.everyHead(mode.manifest, setting).on
            val callers = ModeCaller.entries.filter { ModeRule.everyHead(mode.manifest, setting, it).on }
            val onlyFor = if (every) emptyList() else callers
            ModeRow(mode.name, mode.manifest.summary, every || callers.isNotEmpty(), onlyFor)
        }
    }
}

/** [env] is setup's own, so the backup of splice.toml lands in the state folder that file names. */
internal class SetupModes(
    private val prompts: SetupPrompts,
    private val env: EnvReader,
    private val rows: ModeRows = CarriedModes(),
) {
    /** The modes the person turned on; null when nothing was asked (no console, or no mode is off). */
    fun offer(path: Path): List<String>? {
        if (!prompts.hasConsole()) return null
        val all = Cancellables.runCatchingBestEffort { rows(path) }.getOrDefault(emptyList())
        if (all.none { !it.on }) return null
        val listed = all.joinToString(", ") { "${it.name} (${it.said})" }
        prompts.frame.step("Modes add tools to every session on splice's door: $listed")
        return all.filterNot { it.on }.filter { prompts.frame.confirm("Turn on ${it.name}? ${it.summary}", false) }
            .map { it.name }
    }

    fun summary(on: List<String>): List<String> =
        if (on.isEmpty()) emptyList() else listOf("Modes turned on: ${on.joinToString(", ")}")

    /** `on = true` in each of [on]'s tables, keeping any per-head keys it holds. A write that is refused is said. */
    fun save(path: Path, on: List<String>) {
        if (on.isEmpty()) return
        val topology = Cancellables.runCatchingBestEffort { TopologyLoader.parse(Files.readString(path)) }.getOrNull()
        val backups = TopologyStatePaths(env).of(topology).configBackupsDir
        val writer = TopologyWriter(path, backups, TopologyParse(TopologyLoader::parse))
        for (name in on) {
            val turnOn = ModeTableEdit(setsOn = true, on = true)
            val result = Cancellables.runCatchingBestEffort { ModeTableWrite(writer).write(name, turnOn) }
            val refused = (result.getOrNull() as? TopologyWriteResult.Refused)
                ?.findings?.joinToString("; ") { it.text() }
            val failed = result.exceptionOrNull()?.let { SafeFailureText.render(it) }
            val why = refused ?: failed ?: continue
            System.err.println("splice: mode $name was not turned on ($why); set on = true under [modes.$name]")
        }
    }
}

// The two modes the repository ships to be copied into a new one (modes/README.md, "Adding a mode"): working examples
// that ship off and give a session nothing it would use, so setup never offers them.
private val MODE_TEMPLATES = setOf("template", "templateapp")

// Each caller as the step names it to the person.
private val CALLER_WORDS = mapOf(
    ModeCaller.DOOR to "claude on the door",
    ModeCaller.HEAD to "a command's own sessions",
    ModeCaller.DESKTOP to "Claude Desktop",
)
