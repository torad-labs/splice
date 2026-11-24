package splice.provider.antigravity.skills

import splice.core.util.EnvReader
import splice.core.util.LogSink
import java.nio.file.Path

/** The real [file] and [folder] (null for a command), the declared discovery [root], and the path agy's skill index
 *  names it by ([SkillRoot.indexPath]; null when the index may not name it, and for every command). Keeping them
 *  separate lets the local menu honor every alias while the index names only what its folder's owner may send. */
internal data class SkillPlace(val file: Path, val folder: Path?, val root: Path, val indexPath: Path?)

/** Who may start a skill, from its frontmatter as Claude Code reads it: the person, by typing it (`user-invocable`,
 *  default true), and the model, by choosing it (`disable-model-invocation`, default false). */
internal data class SkillUse(val byUser: Boolean = true, val byModel: Boolean = true)

/** A discovered command or skill's bounded metadata and source, without its body. */
internal data class LocalSkillItem(
    val name: String,
    val description: String,
    val hint: String,
    val fallbackDescription: String,
    val place: SkillPlace,
    val use: SkillUse = SkillUse(),
) {
    val file: Path get() = place.file
    val folder: Path? get() = place.folder
    val root: Path get() = place.root
    val menuDescription: String get() = description.ifBlank { fallbackDescription }

    /** A skill is a folder with a SKILL.md; a command is a single Markdown file. */
    val isSkill: Boolean get() = folder != null
}

/** Discovered local slash commands and skills for one ACP session: [commands] holds every one found, the ones the
 *  person can type and the ones only the model can choose (`user-invocable: false`). */
internal data class LocalSkillCatalog(
    val commands: List<LocalSkillItem>,
    val skillNames: List<String>,
) {
    /** What the person can type: the slash menu, `reload_skills`, `system/init` and `/name` expansion. */
    val typed: List<LocalSkillItem> get() = commands.filter { it.use.byUser }
}

internal class ClaudeSkillCatalog(cwd: Path, env: EnvReader, log: LogSink) {
    private val roots = SkillRoots(cwd, env)
    private val scanner = SkillScanner(log)
    private val manifest = DesktopSkillManifest(log)

    fun load(): LocalSkillCatalog {
        val workspace = roots.workspace()
        val config = roots.config()
        val plugin = roots.pluginDir()?.let { SkillRoot(it.resolve(LOCAL_SKILLS_DIR), manifest.disabledIn(it)) }
        val skills = roots.skillDirs(workspace, config, plugin)
        val commands = scanner.commands(roots.commandDirs(workspace, config))
        val all = winners(scanner.skills(skills) + commands)
        return LocalSkillCatalog(all, all.filter { it.isSkill && it.use.byUser }.map(LocalSkillItem::name))
    }

    /** The workspace roots that agy itself searches, nearest first. */
    fun nativeRoots(): List<Path> =
        roots.workspace().map(roots::nativeUnder).filter { SkillPaths.directory(it) != null }

    /** The skills agy discovers by itself in [nativeRoots], each by its real folder and its name. */
    fun nativeSkills(): List<LocalSkillItem> = scanner.skills(nativeRoots().map { SkillRoot(it) }).toList()

    /** The first of [items] with each name and each real file or folder, in [SkillRoots.skillDirs]'s order: a copy
     *  only ever drops a later one, so a project's copy can take nothing from a person's skill, and a project's
     *  entry that aliases a person's skill is dropped for it. */
    private fun winners(items: Sequence<LocalSkillItem>): List<LocalSkillItem> {
        val names = mutableSetOf<String>()
        val files = mutableSetOf<Path>()
        val folders = mutableSetOf<Path>()
        return items.filter { item ->
            val folder = item.folder
            val freshPath = item.file !in files && (folder == null || folder !in folders)
            val fresh = item.name !in names && freshPath
            if (fresh) {
                names += item.name
                files.add(item.file)
                if (folder != null) folders.add(folder)
            }
            fresh
        }.toList()
    }
}
