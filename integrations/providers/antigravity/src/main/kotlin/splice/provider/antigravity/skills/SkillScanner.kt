package splice.provider.antigravity.skills

import splice.core.util.LogSink
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path

internal data class SkillRoot(val path: Path, val disabled: Set<String> = emptySet())

internal class SkillScanner(private val log: LogSink) {
    private val frontmatter = SkillFrontmatter()

    fun skills(roots: List<SkillRoot>): Sequence<LocalSkillItem> =
        roots.asSequence()
            .mapNotNull { root -> SkillPaths.directory(root.path)?.let { root.copy(path = it) } }
            .distinctBy(SkillRoot::path)
            .flatMap { root -> children(root.path).asSequence().mapNotNull { skill(it, root) } }

    fun commands(roots: List<Path>): Sequence<LocalSkillItem> =
        roots.asSequence().mapNotNull(SkillPaths::directory).distinct()
            .flatMap { dir -> children(dir).asSequence().mapNotNull { command(it, dir) } }

    private fun skill(child: Path, root: SkillRoot): LocalSkillItem? {
        val disabled = root.disabled
        val name = SkillName.valid(child.fileName.toString())?.takeUnless(disabled::contains) ?: return null
        val folder = SkillPaths.directory(child) ?: return null
        return SkillPaths.file(folder.resolve(SKILL_DOC))
            ?.let { frontmatter.readItem(it, name, folder, root.path) }
            ?.takeUnless { it.name in disabled }
    }

    private fun command(child: Path, root: Path): LocalSkillItem? {
        val leaf = child.fileName.toString().takeIf { it.endsWith(COMMAND_EXT) } ?: return null
        val name = SkillName.valid(leaf.removeSuffix(COMMAND_EXT)) ?: return null
        return SkillPaths.file(child)?.let { frontmatter.readItem(it, name, folder = null, root = root) }
    }

    private fun children(dir: Path): List<Path> = try {
        Files.list(dir).use { stream -> stream.limit(MAX_ENTRIES_PER_ROOT).toList() }.sorted()
    } catch (_: IOException) {
        missingChildren(dir)
    } catch (_: UncheckedIOException) {
        missingChildren(dir)
    }

    private fun missingChildren(dir: Path): List<Path> {
        log("[antigravity] could not list ${dir.fileName}\n")
        return emptyList()
    }
}

internal object SkillName {
    fun valid(text: String): String? = text.takeIf { NAME_PATTERN.matches(it) }
}

private const val SKILL_DOC = "SKILL.md"
private const val COMMAND_EXT = ".md"

// why: a crowded directory must not monopolize the Desktop initialization handshake.
private const val MAX_ENTRIES_PER_ROOT = 1_000L
private val NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
