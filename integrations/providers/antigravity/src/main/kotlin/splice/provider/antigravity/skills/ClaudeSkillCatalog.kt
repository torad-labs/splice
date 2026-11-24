package splice.provider.antigravity.skills

import splice.core.util.EnvReader
import splice.core.util.LogSink
import java.nio.file.Path

/** A discovered command or skill, including its bounded Markdown and canonical source: [file] and [folder] are real
 *  paths, and [root] is the real path of the skills or commands folder it was found in, whatever its own entry links
 *  to (which is how agy, finding it in that folder, sees it). */
internal data class LocalSkillItem(
    val name: String,
    val description: String,
    val hint: String,
    val body: String,
    val file: Path,
    val folder: Path?,
    val root: Path,
) {
    /** A skill is a folder with a SKILL.md; a command is a single Markdown file. */
    val isSkill: Boolean get() = folder != null
}

/** Discovered local slash commands and skills for one ACP session. */
internal data class LocalSkillCatalog(
    val commands: List<LocalSkillItem>,
    val skillNames: List<String>,
)

internal class ClaudeSkillCatalog(cwd: Path, env: EnvReader, log: LogSink) {
    private val roots = SkillRoots(cwd, env)
    private val scanner = SkillScanner(log)
    private val manifest = DesktopSkillManifest(log)

    fun load(): LocalSkillCatalog {
        val workspace = roots.workspace()
        val config = roots.config()
        val skills = roots.skillDirs(workspace, config).map { SkillRoot(it) }
        val plugin = roots.pluginDir()?.let { SkillRoot(it.resolve(LOCAL_SKILLS_DIR), manifest.disabledIn(it)) }
        val commands = scanner.commands(roots.commandDirs(workspace, config))
        val all = winners(scanner.skills(skills + listOfNotNull(plugin)) + commands)
        return LocalSkillCatalog(all, all.filter(LocalSkillItem::isSkill).map(LocalSkillItem::name))
    }

    /** The workspace roots that agy itself searches, nearest first. */
    fun nativeRoots(): List<Path> =
        roots.workspace().mapNotNull { SkillPaths.directory(roots.nativeUnder(it)) }.distinct()

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
