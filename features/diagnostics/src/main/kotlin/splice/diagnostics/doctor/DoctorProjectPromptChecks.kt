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

import splice.core.prompt.SystemPromptMode
import splice.core.topology.Topology
import java.nio.file.Files
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
     *  Declarations are read off the schema and prompt files are never opened, as in
     *  DoctorConfigChecks.systemPromptChecks. */
    internal fun projectPromptChecks(topology: Topology): List<DoctorCheck> =
        topology.projects.flatMap { (key, project) ->
            val raw = key.trim('"')
            val home = System.getProperty("user.home")
            val root = Paths.get(if (raw.startsWith("~/")) home + raw.substring(1) else raw)
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
                Triple(
                    "project:$raw",
                    project.systemPromptMode,
                    projectSource(project.systemPrompt, project.systemPromptFile),
                ),
            ) + project.heads.map { (head, prompt) ->
                Triple(
                    "project-head:$raw:$head",
                    prompt.systemPromptMode,
                    projectSource(prompt.systemPrompt, prompt.systemPromptFile),
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
            missing + layers.mapNotNull { (name, mode, source) -> source?.let { layerRow(name, mode, it) } }
        }

    private fun layerRow(name: String, mode: SystemPromptMode?, source: String): DoctorCheck = when (mode) {
        SystemPromptMode.REPLACE -> DoctorCheck(
            "project-prompt:$name",
            CheckStatus.WARN,
            "$name sets system_prompt_mode = \"replace\" ($source): the client's own system field and every " +
                "earlier layer are substituted, and Claude Code ships its entire operating instruction set in " +
                "that field, so sessions in this project run as a bare model with tools attached",
            replaceFix,
        )
        // V4-171: the same row for strip — the client's instructions are edited, and what a pattern
        // removes is the operator's to own; splice ships no pattern list.
        SystemPromptMode.STRIP -> DoctorCheck(
            "project-prompt:$name",
            CheckStatus.WARN,
            "$name sets system_prompt_mode = \"strip\" ($source): the client's own system field is edited on " +
                "every turn in this project — each paragraph a pattern matches is removed, and what is removed " +
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
