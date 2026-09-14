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
    )

    private data class ProjectRule(
        val path: Path,
        val model: String?,
        val rule: Rule,
    )

    private val global: Rule?
    private val models: Map<String, Rule>
    private val projects: List<ProjectRule>

    init {
        require(config.model.all { it.model.isNotBlank() }) { "compaction model must not be blank" }
        require(config.model.map { it.model }.distinct().size == config.model.size) {
            "compaction model entries must be unique"
        }
        val normalizedProjects = config.project.map { entry ->
            require(entry.path.isNotBlank()) { "compaction project path must not be blank" }
            require(entry.model == null || entry.model.isNotBlank()) { "compaction project model must not be blank" }
            val path = Paths.get(entry.path).normalize()
            require(path.isAbsolute) { "compaction project path must be absolute: ${entry.path}" }
            path
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
        val normalized = project?.normalize()?.takeIf { it.isAbsolute }
        val selected = normalized?.let { path ->
            projectRule(path, model) ?: projectRule(path, null) ?: models[model] ?: global
        } ?: global
        return selected?.let { EffectiveCompactionInstructions(it.text, it.scope, it.source) }
            ?: EffectiveCompactionInstructions(null, CompactionScope.CLIENT, CompactionScope.CLIENT.wire)
    }

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

        val path = resolveFile(file)
        return Cancellables.runCatchingCancellable { readFile(path) }
            .fold(
                onSuccess = { Rule(it, scope, "$source file:$path") },
                onFailure = { failure ->
                    log(
                        "[compaction] $path unreadable (${SafeFailureText.render(failure)}) — " +
                            "custom instructions disabled for $source\n",
                    )
                    Rule(null, scope, "$source file:$path unreadable")
                },
            )
    }

    private fun resolveFile(raw: String): Path {
        val expanded = if (raw.startsWith("~/")) {
            System.getProperty("user.home") + raw.substring(1)
        } else {
            raw
        }
        val path = Paths.get(expanded)
        return if (path.isAbsolute) path.normalize() else configDir.resolve(path).normalize()
    }
}
