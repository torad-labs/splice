// NEW: desktop-3p-parity (Nov 23, 2026). Where DesktopSkillsSync finds skills and the skills plugins it gives them to:
// the plugins Claude Desktop made in a profile, the skills the person made in Desktop, and the skills in Claude Code's
// folder, each with what its `SKILL.md` front matter says it is for. Every listing is bounded, a name is taken only
// when it is one folder name, and nothing here writes.
package splice.app.desktop.setup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class DesktopSkillOffers(private val files: DesktopLibraryFiles) {
    /** The skills plugins Desktop made in [appData]: each `<org>/<account>` folder of its skills plugin that holds a
     *  manifest. */
    fun plugins(appData: Path): List<Path> =
        folders(appData.resolve(SKILLS_PLUGIN_DIR)).flatMap(::folders)
            .filter { Files.isRegularFile(it.resolve(SKILLS_MANIFEST), LinkOption.NOFOLLOW_LINKS) }

    /** The skills the person made in the Desktop whose profile is [appData], as its plugins list them. */
    fun desktopSkills(appData: Path): List<DesktopSkill> = plugins(appData).flatMap { plugin ->
        val listed = files.read(plugin.resolve(SKILLS_MANIFEST))?.get(MANIFEST_SKILLS) as? JsonArray
        listed.orEmpty().mapNotNull { element ->
            val entry = (element as? JsonObject)?.takeIf { JsonScalars.str(it, SKILL_CREATOR) == SKILL_CREATOR_USER }
            folderName(JsonScalars.str(entry, SKILL_NAME))?.let { name ->
                plugin.resolve(SKILLS_FOLDER).resolve(name).takeIf(::isSkill)
                    ?.let { DesktopSkill(name, it, JsonScalars.str(entry, SKILL_DESCRIPTION_KEY).orEmpty()) }
            }
        }
    }

    /** The skills in Claude Code's skills folder [dir]; a skill linked there is the folder it links to, as Claude Code
     *  loads it. */
    fun codeSkills(dir: Path): List<DesktopSkill> = listed(dir).mapNotNull { child ->
        folderName(child.fileName.toString())?.let { name ->
            real(child)?.takeIf(::isSkill)?.let { DesktopSkill(name, it, said(it)) }
        }
    }

    /** [path] with every link followed, or null when it leads nowhere. */
    private fun real(path: Path): Path? = try {
        path.toRealPath()
    } catch (_: IOException) {
        null
    }

    /** Whether [folder] is a real folder holding a `SKILL.md`. */
    private fun isSkill(folder: Path): Boolean = Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) &&
        Files.isRegularFile(folder.resolve(SKILL_MD_FILE), LinkOption.NOFOLLOW_LINKS)

    /** What [folder]'s `SKILL.md` front matter says the skill is for, a folded or literal block joined into one line;
     *  empty when it says nothing. */
    private fun said(folder: Path): String {
        val head = try {
            Files.newBufferedReader(folder.resolve(SKILL_MD_FILE)).use { reader ->
                generateSequence { reader.readLine() }.take(FRONT_MATTER_LINES).toList()
            }
        } catch (_: IOException) {
            emptyList()
        }
        val front = head.takeIf { it.firstOrNull()?.trim() == FRONT_MATTER_FENCE }.orEmpty()
            .drop(1).takeWhile { it.trim() != FRONT_MATTER_FENCE }
        val at = front.indexOfFirst { it.startsWith("$SKILL_DESCRIPTION_KEY:") }
        val inline = front.getOrNull(at)?.substringAfter(':')?.trim().orEmpty()
        val text = if (inline in BLOCK_MARKERS) {
            front.drop(at + 1).takeWhile { it.isBlank() || it.first().isWhitespace() }.joinToString(" ") { it.trim() }
        } else {
            inline
        }
        return text.trim().removeSurrounding("\"").removeSurrounding("'")
    }

    /** The real folders in [dir], bounded and sorted. */
    private fun folders(dir: Path): List<Path> = listed(dir).filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }

    /** What [dir] holds, bounded and sorted; nothing when it is not a real folder. */
    private fun listed(dir: Path): List<Path> = if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
        Files.list(dir).use { stream -> stream.limit(SKILLS_MAX_LISTED.toLong()).toList() }.sorted()
    } else {
        emptyList()
    }

    /** [name] when it is one folder name a skill can have, or null. */
    private fun folderName(name: String?): String? = name?.takeIf { SKILL_FOLDER_NAME.matches(it) }
}

private const val SKILLS_PLUGIN_DIR = "local-agent-mode-sessions/skills-plugin"
private const val SKILL_MD_FILE = "SKILL.md"
private const val FRONT_MATTER_FENCE = "---"
private const val SKILL_DESCRIPTION_KEY = "description"
private val BLOCK_MARKERS = setOf(">", ">-", ">+", "|", "|-", "|+")
private val SKILL_FOLDER_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

// why: front matter sits at the top of a SKILL.md; past two hundred lines it is no front matter, and a skill's body
// can run to thousands of lines that need not be read.
private const val FRONT_MATTER_LINES = 200

// why: bounds each folder listing; Claude Code's skills folder holds about a hundred skills, a Desktop profile a few
// skills plugins.
private const val SKILLS_MAX_LISTED = 1_000
