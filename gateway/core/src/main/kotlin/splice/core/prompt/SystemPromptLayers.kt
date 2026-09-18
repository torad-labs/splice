// NEW: V4-124 (operator feature 2026-09-18, FEATURES.md s13) — the three standing-prompt LAYERS a
// turn can carry: the head's own (V4-36, [heads.KEY]), the project's ([projects."ROOT"]) and the
// project's layer for this one head ([projects."ROOT".heads.KEY]).
//
// DERIVE, NOT RE-AUTHOR: every layer is a [HeadSystemPrompt] — the same inline-or-file idiom, the
// same both-set and unreadable-file load errors, the same "empty is no prompt" rule — with only its
// source and its file directory changed. A project layer's relative file resolves against the
// project root, so a prompt can live in the repo it governs.
//
// THE FOLD, in order head -> project -> project-head: an append layer is added after whatever is
// already there; a replace layer drops everything before it (the client's field, which the dialect
// seam drops for it, AND every earlier layer). Later appends still land after a replace. The result
// is the list of layers the dialect seam applies one after another, so each append stays its own
// trailing block and the client's cache breakpoints are never moved.
package splice.core.prompt

import splice.core.topology.ProjectConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the layers for one head. Built once at load: every project file is read here, so an
 * unreadable file or a both-set table is a config error at daemon start rather than a prompt that
 * silently never rides. A project key that is not absolute after `~/` expansion is a config error
 * for the same reason — a relative root would match a different tree depending on where the daemon
 * happened to start.
 */
public class SystemPromptLayers(
    head: HeadSystemPrompt,
    projects: Map<String, ProjectConfig> = emptyMap(),
    headKey: String = "",
    home: String = System.getProperty("user.home"),
    readFile: SystemPromptFileRead = SystemPromptFileRead { Files.readString(it) },
) {
    private val headLayer: EffectiveSystemPrompt? = head.resolve()

    /** Deepest first, so the first ancestor match is the deepest configured root (nested repos). */
    private val roots: List<ProjectLayers> = projects
        .map { (key, project) -> projectLayers(key, project, headKey, home, readFile) }
        .sortedByDescending { it.root.nameCount }

    /** Whether any project is configured: with none, a turn needs no session lookup at all. */
    public val hasProjects: Boolean
        get() = roots.isNotEmpty()

    /** The layers a turn in [cwd] carries, in wire order. A null [cwd] (a session whose directory
     *  could not be resolved) gets the head layer only — never a project chosen by a guess. */
    public fun resolve(cwd: Path?): List<EffectiveSystemPrompt> {
        val dir = cwd?.normalize()
        val project = dir?.let { path -> roots.firstOrNull { path.startsWith(it.root) } }
        val stack = listOfNotNull(headLayer, project?.project, project?.projectHead)
        return stack.fold(emptyList()) { kept, layer ->
            if (layer.mode == SystemPromptMode.REPLACE) listOf(layer) else kept + layer
        }
    }

    private fun projectLayers(
        key: String,
        project: ProjectConfig,
        headKey: String,
        home: String,
        readFile: SystemPromptFileRead,
    ): ProjectLayers {
        val root = projectRoot(key, home)
        val headPrompt = project.heads[headKey]
        return ProjectLayers(
            root = root,
            project = HeadSystemPrompt(
                text = project.systemPrompt,
                file = project.systemPromptFile,
                mode = project.systemPromptMode ?: SystemPromptMode.APPEND,
                configDir = root,
                readFile = readFile,
                source = "project:$root",
            ).resolve(),
            projectHead = headPrompt?.let {
                HeadSystemPrompt(
                    text = it.systemPrompt,
                    file = it.systemPromptFile,
                    mode = it.systemPromptMode ?: SystemPromptMode.APPEND,
                    configDir = root,
                    readFile = readFile,
                    source = "project-head:$root:$headKey",
                ).resolve()
            },
        )
    }

    /** ktoml hands a quoted table key back WITH its quotes (the same trap ProviderConfig.staticHeaders
     *  strips), and a root is always written quoted because it contains slashes. */
    private fun projectRoot(key: String, home: String): Path {
        val raw = key.trim('"')
        val expanded = if (raw.startsWith("~/")) home + raw.substring(1) else raw
        val path = Paths.get(expanded)
        require(path.isAbsolute) {
            "[projects.\"$raw\"] must name an absolute project root (or one under ~/)"
        }
        return path.normalize()
    }
}

/** One configured root and the two layers it contributes for this head (either may be absent). */
private data class ProjectLayers(
    val root: Path,
    val project: EffectiveSystemPrompt?,
    val projectHead: EffectiveSystemPrompt?,
)
