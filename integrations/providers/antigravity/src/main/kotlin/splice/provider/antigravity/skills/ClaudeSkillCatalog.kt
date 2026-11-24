// NEW: desktop-antigravity (ACP skill autocomplete). Discovers Claude Code user and project skills and slash commands
// so `splice desktop acp` reports them in `initialize` (`commands`) and `system/init` (`slash_commands`, `skills`),
// matching real Claude Code's autocomplete in Claude Desktop. Reads only Claude's own config dir (`CLAUDE_CONFIG_DIR`
// or `~/.claude`) and the session workspace (`<cwd>/.claude`, `<cwd>/.agents`), never `~/.gemini`.
package splice.provider.antigravity.skills

import splice.core.config.UserHome
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** One local skill or slash command exposed to Claude Desktop autocomplete. */
internal data class LocalSkillItem(
    val name: String,
    val description: String,
    val hint: String,
    val isSkill: Boolean,
)

/** Discovered local slash commands and skills for one ACP session. */
internal data class LocalSkillCatalog(
    val commands: List<LocalSkillItem>,
    val skillNames: List<String>,
)

/** Loads user and project skills (`skills/<name>/SKILL.md`) and slash commands (`commands/<name>.md`). */
internal class ClaudeSkillCatalog(
    private val cwd: Path,
    private val claudeConfigDir: Path,
    private val log: LogSink,
) {
    private val frontmatter = SkillFrontmatter()

    fun load(): LocalSkillCatalog {
        val merged = LinkedHashMap<String, LocalSkillItem>()
        val sources = listOf(
            scanSkills(cwd.resolve(UserHome.CLAUDE_DIR).resolve(ACP_SKILLS_SUBDIR)),
            scanSkills(cwd.resolve(ACP_AGENTS_DIR).resolve(ACP_SKILLS_SUBDIR)),
            scanCommands(cwd.resolve(UserHome.CLAUDE_DIR).resolve(ACP_COMMANDS_SUBDIR)),
            scanSkills(claudeConfigDir.resolve(ACP_SKILLS_SUBDIR)),
            scanCommands(claudeConfigDir.resolve(ACP_COMMANDS_SUBDIR)),
        )
        sources.flatten().forEach { item -> merged.putIfAbsent(item.name, item) }
        val all = merged.values.toList()
        return LocalSkillCatalog(all, all.filter(LocalSkillItem::isSkill).map(LocalSkillItem::name))
    }

    private fun scanSkills(dir: Path): List<LocalSkillItem> = listBounded(dir).mapNotNull { child ->
        val folderName = validName(child.fileName?.toString())
        val target = folderName?.let { resolveReal(child) }
        val skillFile = target?.takeIf { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            ?.resolve(ACP_SKILL_DOC)
            ?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        if (folderName != null && skillFile != null) {
            frontmatter.readItem(skillFile, folderName, isSkill = true)
        } else {
            null
        }
    }

    private fun scanCommands(dir: Path): List<LocalSkillItem> = listBounded(dir).mapNotNull { child ->
        val fileName = child.fileName?.toString().orEmpty()
        val stem = fileName.takeIf { it.endsWith(ACP_COMMAND_EXT) }?.removeSuffix(ACP_COMMAND_EXT)
        val defaultName = validName(stem)
        val realFile = defaultName?.let { resolveReal(child) }
            ?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        if (defaultName != null && realFile != null) {
            frontmatter.readItem(realFile, defaultName, isSkill = false)
        } else {
            null
        }
    }

    private fun listBounded(dir: Path): List<Path> {
        val resolved = resolveReal(dir) ?: return emptyList()
        if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return try {
            Files.list(resolved).use { stream ->
                stream.limit(ACP_MAX_SKILL_ENTRIES.toLong()).toList()
            }.sorted()
        } catch (_: IOException) {
            log("[antigravity] could not list ${dir.fileName}\n")
            emptyList()
        }
    }

    private fun resolveReal(path: Path): Path? = try {
        path.toRealPath()
    } catch (_: IOException) {
        null
    }

    private fun validName(raw: String?): String? = raw?.takeIf { ACP_ENTRY_NAME_REGEX.matches(it) }
}

/** Parses bounded YAML frontmatter from a skill or command markdown file. */
internal class SkillFrontmatter {
    fun readItem(file: Path, fallbackName: String, isSkill: Boolean): LocalSkillItem? {
        val front = readFrontmatterLines(file)
        val invocable = scalar(front, ACP_FM_USER_INVOCABLE)
        if (invocable.equals("false", ignoreCase = true)) return null
        val declared = scalar(front, ACP_FM_NAME).takeIf { ACP_ENTRY_NAME_REGEX.matches(it) }
        return LocalSkillItem(
            name = declared ?: fallbackName,
            description = scalar(front, ACP_FM_DESCRIPTION),
            hint = scalar(front, ACP_FM_ARGUMENT_HINT),
            isSkill = isSkill,
        )
    }

    private fun readFrontmatterLines(file: Path): List<String> {
        val head = try {
            Files.newBufferedReader(file).use { reader ->
                generateSequence { reader.readLine() }.take(ACP_MAX_FM_LINES).toList()
            }
        } catch (_: IOException) {
            emptyList()
        }
        return head.takeIf { it.firstOrNull()?.trim() == ACP_FM_FENCE }.orEmpty()
            .drop(1)
            .takeWhile { it.trim() != ACP_FM_FENCE }
    }

    private fun scalar(front: List<String>, key: String): String {
        val prefix = "$key:"
        val index = front.indexOfFirst { it.startsWith(prefix) }
        if (index < 0) return ""
        val inline = front[index].substringAfter(':').trim()
        val raw = if (inline in ACP_BLOCK_MARKERS) {
            front.drop(index + 1)
                .takeWhile { it.isBlank() || it.first().isWhitespace() }
                .joinToString(" ") { it.trim() }
        } else {
            inline
        }
        return raw.trim().removeSurrounding("\"").removeSurrounding("'")
    }
}

private const val ACP_AGENTS_DIR = ".agents"
private const val ACP_SKILLS_SUBDIR = "skills"
private const val ACP_COMMANDS_SUBDIR = "commands"
private const val ACP_SKILL_DOC = "SKILL.md"
private const val ACP_COMMAND_EXT = ".md"
private const val ACP_FM_FENCE = "---"
private const val ACP_FM_NAME = "name"
private const val ACP_FM_DESCRIPTION = "description"
private const val ACP_FM_ARGUMENT_HINT = "argument-hint"
private const val ACP_FM_USER_INVOCABLE = "user-invocable"
private val ACP_BLOCK_MARKERS = setOf(">", ">-", ">+", "|", "|-", "|+")
private val ACP_ENTRY_NAME_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

// why: frontmatter sits at the top of a SKILL.md or command file; capping line reads keeps large bodies fast.
private const val ACP_MAX_FM_LINES = 200

// why: caps per-directory scans so a huge folder cannot stall ACP initialize handshake.
private const val ACP_MAX_SKILL_ENTRIES = 1_000
