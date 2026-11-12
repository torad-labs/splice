// NEW: V4-156 — the doctor's project prompt layer rows (V4-124), moved verbatim out of
// DoctorConfigChecks.kt. V4-124 added them there as a real feature and the file grew into
// concentration band HIGH by its own growth; the section reads only the topology's [projects]
// table, shares nothing with the other configuration checks but the REPLACE warning's fix text, and
// so stands alone as its own collaborator. DoctorConfigChecks still orders it into the section.
//
// IN A SUBPACKAGE, not beside DoctorConfigChecks: splice.app.cli is the repo's most crowded package
// and the concentration ratchet gates its file count, so a decomposition that dropped two more files
// into it would have moved the crowding rather than reduced it. splice.app.cli.prompt was already
// here, so the shape is the tree's own.
package splice.diagnostics.doctor

import splice.core.config.UserHome
import splice.core.prompt.SystemPromptMode
import splice.core.topology.ProjectConfig
import splice.core.topology.Topology
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** [replaceFix] is DoctorConfigChecks' fix text for a REPLACE layer, and [stripFix] its remedy for a
 *  STRIP layer — whose value is a regex list, so "use append instead" would ship the patterns to the
 *  model as prompt text (V4-172). Passed in so both sections
 *  give the one remedy from its one declaration. */
internal class DoctorProjectPromptChecks(private val replaceFix: String, private val stripFix: String) {

    /** V4-124: one row per project layer (the project's own, then each per-head one) naming its
     *  root, mode and source. A layer set to replace is a WARN for the same reason a head's is. A
     *  root missing on disk is a WARN: the layer can never match a session, but the config is legal.
     *  A root that is not absolute after `~/` is a FAIL, because the daemon refuses it at load.
     *  Declarations are read off the schema. A layer's prompt file is checked for being a readable file, never read:
     *  the daemon refuses to load one it cannot read (spec section 13). */
    internal fun projectPromptChecks(topology: Topology): List<DoctorCheck> =
        topology.projects.flatMap { (key, project) ->
            val raw = key.trim('"')
            val root = Paths.get(UserHome.expand(raw))
            if (!root.isAbsolute) {
                return@flatMap listOf(
                    DoctorCheck(
                        "project-prompt:$raw",
                        CheckStatus.FAIL,
                        "[projects.\"$raw\"] is not an absolute path, so the daemon refuses to load it",
                        "use an absolute project root, or one that starts with ~/",
                    ),
                )
            }
            val layers = listOf(
                Layer(
                    "project:$raw",
                    project.systemPromptMode,
                    projectSource(project.systemPrompt, project.systemPromptFile),
                    project.systemPromptFile,
                ),
            ) + project.heads.map { (head, prompt) ->
                Layer(
                    "project-head:$raw:$head",
                    prompt.systemPromptMode,
                    projectSource(prompt.systemPrompt, prompt.systemPromptFile),
                    prompt.systemPromptFile,
                )
            }
            val missing = if (Files.isDirectory(root)) {
                emptyList()
            } else {
                listOf(
                    DoctorCheck(
                        "project-prompt:$raw",
                        CheckStatus.WARN,
                        "project root $root does not exist, so its prompt layers never apply",
                        "fix the [projects.\"$raw\"] key, or remove the table",
                    ),
                )
            }
            val rows = layers.mapNotNull { layer -> layer.source?.let { layerRow(layer, root) } }
            missing + bothKeys(raw, project) + rows
        }

    /** A table that sets both system_prompt and system_prompt_file is a load error, never a silent precedence. */
    private fun bothKeys(raw: String, project: ProjectConfig): List<DoctorCheck> {
        val named = listOf("project:$raw" to project.systemPrompt) + project.heads.map { (head, p) ->
            "project-head:$raw:$head" to p.systemPrompt
        }
        val files = listOf(project.systemPromptFile) + project.heads.values.map { it.systemPromptFile }
        return named.zip(files).filter { (table, file) -> table.second != null && file != null }.map { (table) ->
            DoctorCheck(
                "project-prompt:${table.first}",
                CheckStatus.FAIL,
                "${table.first} sets both system_prompt and system_prompt_file, so the daemon refuses to load it",
                "keep one of system_prompt or system_prompt_file in that table",
            )
        }
    }

    /** One layer as the doctor reads it: its row name, mode, where its text comes from, and the file when it is one. */
    private data class Layer(val name: String, val mode: SystemPromptMode?, val source: String?, val file: String?)

    /** The layer's row. A prompt file the daemon cannot read is a load error, so it is a FAIL whatever the mode. */
    private fun layerRow(layer: Layer, root: Path): DoctorCheck {
        val path = layer.file?.let { resolvedFile(it, root) }
        if (path != null && !readable(path)) {
            return DoctorCheck(
                "project-prompt:${layer.name}",
                CheckStatus.FAIL,
                "${layer.name} system_prompt_file is unreadable: $path, so the daemon refuses to load it",
                "create the file, fix the path (a relative one is under the project root), or remove the key",
            )
        }
        return modeRow(layer.name, layer.mode, requireNotNull(layer.source))
    }

    private fun readable(path: Path): Boolean = Files.isRegularFile(path) && Files.isReadable(path)

    /** Where the daemon looks for a layer's file: `~/` is home, a relative path is under the project root. */
    private fun resolvedFile(raw: String, root: Path): Path {
        val path = Paths.get(UserHome.expand(raw))
        return if (path.isAbsolute) path.normalize() else root.resolve(path).normalize()
    }

    private fun modeRow(name: String, mode: SystemPromptMode?, source: String): DoctorCheck = when (mode) {
        SystemPromptMode.REPLACE -> DoctorCheck(
            "project-prompt:$name",
            CheckStatus.INFO,
            "$name sets system_prompt_mode = \"replace\" ($source): the client's own system field and every " +
                "earlier layer are substituted, and Claude Code ships its entire operating instruction set in " +
                "that field, so sessions in this project run as a bare model with tools attached",
            replaceFix,
        )
        // V4-171: the same row for strip — the client's instructions are edited, and what a pattern
        // removes is the operator's to own; splice ships no pattern list.
        SystemPromptMode.STRIP -> DoctorCheck(
            "project-prompt:$name",
            CheckStatus.INFO,
            "$name sets system_prompt_mode = \"strip\" ($source): the client's own system field is edited on " +
                "every turn in this project; each paragraph a pattern matches is removed, and what is removed " +
                "is yours to own",
            stripFix,
        )
        SystemPromptMode.APPEND, null -> DoctorCheck("project-prompt:$name", CheckStatus.OK, "$name append ($source)")
    }

    /** Where a layer's text comes from, or null when it configures none (empty is no prompt). */
    private fun projectSource(text: String?, file: String?): String? = when {
        file != null -> "file:$file"
        !text.isNullOrEmpty() -> "inline"
        else -> null
    }
}
