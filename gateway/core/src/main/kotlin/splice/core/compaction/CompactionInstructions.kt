// NEW: v0.4.0 FEATURES.md §7 — the boot-time resolver for custom compaction text: global, per-model and
// per-project rows, chosen per request so a model switch selects the new rule at once.
package splice.core.compaction

import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime

/** Immutable boot-time resolver for custom compaction text. Resolution is per request, so a model
 *  switch changes the selected rule immediately without sharing mutable session state. */
/** Reads one instructions file named by `file =` in the config. */
public fun interface CompactionFileRead {
    public operator fun invoke(path: Path): String
}

public class CompactionInstructions(
    config: CompactionConfig = CompactionConfig(),
    private val configDir: Path = Paths.get(System.getProperty("user.home"), ".config", "splice"),
    private val readFile: CompactionFileRead = CompactionFileRead { Files.readString(it) },
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    private data class Rule(
        val text: String?,
        val scope: CompactionScope,
        val source: String,
        /** The `file =` behind [text], re-read when it changes; null for inline text. */
        val file: Path? = null,
    )

    private data class FileText(val modified: FileTime?, val text: String?)

    private data class ProjectRule(
        val path: Path,
        val model: String?,
        val rule: Rule,
    )

    private val global: Rule?
    private val models: Map<String, Rule>
    private val projects: List<ProjectRule>
    private val files = HashMap<Path, FileText>()

    init {
        require(config.model.all { it.model.isNotBlank() }) { "compaction model must not be blank" }
        require(config.model.map { it.model }.distinct().size == config.model.size) {
            "compaction model entries must be unique"
        }
        val normalizedProjects = config.project.map { entry ->
            require(entry.path.isNotBlank()) { "compaction project path must not be blank" }
            require(entry.model == null || entry.model.isNotBlank()) { "compaction project model must not be blank" }
            // Resolved like file=: `~/` is the home directory and a relative path is under the
            // topology's directory, so a tilde no longer throws out of the daemon's constructor.
            resolvePath(entry.path)
        }
        val projectKeys = config.project.indices.map { index ->
            normalizedProjects[index] to config.project[index].model
        }
        require(projectKeys.distinct().size == projectKeys.size) {
            "compaction project entries must be unique by path and model"
        }

        global = ruleFor(config.instructions, config.file, CompactionScope.GLOBAL, "global")
        models = config.model.mapNotNull { entry ->
            ruleFor(
                entry.instructions,
                entry.file,
                CompactionScope.MODEL,
                "model:${entry.model}",
            )?.let { entry.model to it }
        }.toMap()
        projects = config.project.mapIndexedNotNull { index, entry ->
            val scope = if (entry.model == null) CompactionScope.PROJECT else CompactionScope.PROJECT_MODEL
            val source = if (entry.model == null) {
                "project:${normalizedProjects[index]}"
            } else {
                "project:${normalizedProjects[index]} model:${entry.model}"
            }
            ruleFor(entry.instructions, entry.file, scope, source)?.let { rule ->
                ProjectRule(normalizedProjects[index], entry.model, rule)
            }
        }
    }

    /** project/model > project > model > global. Within either project tier, the longest matching
     *  absolute path wins. An unknown project uses global directly; empty text remains an opt-out. */
    public fun resolve(model: String, project: Path?): EffectiveCompactionInstructions {
        val normalized = project?.normalize()?.takeIf { it.isAbsolute }?.let(::realPath)
        val selected = normalized?.let { path ->
            projectRule(path, model) ?: projectRule(path, null) ?: models[model] ?: global
        } ?: global
        return selected?.let { EffectiveCompactionInstructions(currentText(it), it.scope, it.source) }
            ?: EffectiveCompactionInstructions(null, CompactionScope.CLIENT, CompactionScope.CLIENT.wire)
    }

    /** A file rule's text as of now: re-read when the file's modification time moved since the last
     *  read (one stat per compaction), so an edit is live without a restart and a file that becomes
     *  unreadable disables the rule the way it would have at boot (review 2026-09-14). */
    private fun currentText(rule: Rule): String? {
        val file = rule.file ?: return rule.text
        val modified = Cancellables.runCatchingCancellable { Files.getLastModifiedTime(file) }.getOrNull()
        synchronized(files) {
            val cached = files[file]
            if (cached != null && cached.modified == modified) return cached.text
            val text = Cancellables.runCatchingCancellable { readFile(file) }
                .onFailure { failure ->
                    log(
                        "[compaction] $file unreadable (${SafeFailureText.render(failure)}) — " +
                            "custom instructions disabled for ${rule.source}\n",
                    )
                }
                .getOrNull()
            files[file] = FileText(modified, text)
            return text
        }
    }

    /** The physical path when it exists (Claude Code records `getcwd`, which resolves symlinks), else
     *  the path as given. */
    private fun realPath(path: Path): Path =
        Cancellables.runCatchingCancellable { path.toRealPath() }.getOrDefault(path)

    private fun projectRule(project: Path, model: String?): Rule? = projects.asSequence()
        .filter { it.model == model && project.startsWith(it.path) }
        .maxByOrNull { it.path.nameCount }
        ?.rule

    private fun ruleFor(
        instructions: String?,
        file: String?,
        scope: CompactionScope,
        source: String,
    ): Rule? {
        require(instructions == null || file == null) { "$source cannot set both instructions and file" }
        if (instructions != null) return Rule(instructions, scope, source)
        if (file == null) return null

        val path = resolvePath(file)
        return Cancellables.runCatchingCancellable { readFile(path) }
            .fold(
                onSuccess = { text ->
                    val modified = Cancellables.runCatchingCancellable { Files.getLastModifiedTime(path) }.getOrNull()
                    synchronized(files) { files[path] = FileText(modified, text) }
                    Rule(text, scope, "$source file:$path", path)
                },
                onFailure = { failure ->
                    log(
                        "[compaction] $path unreadable (${SafeFailureText.render(failure)}) — " +
                            "custom instructions disabled for $source\n",
                    )
                    Rule(null, scope, "$source file:$path unreadable")
                },
            )
    }

    private fun resolvePath(raw: String): Path {
        val expanded = if (raw.startsWith("~/")) {
            System.getProperty("user.home") + raw.substring(1)
        } else {
            raw
        }
        val path = Paths.get(expanded)
        return realPath(if (path.isAbsolute) path.normalize() else configDir.resolve(path).normalize())
    }
}
