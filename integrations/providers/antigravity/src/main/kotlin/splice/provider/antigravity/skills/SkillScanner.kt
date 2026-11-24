package splice.provider.antigravity.skills

import splice.core.util.LogSafe
import splice.core.util.LogSink
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path

internal data class SkillRoot(val path: Path, val disabled: Set<String> = emptySet())

internal class SkillScanner(private val log: LogSink) {
    private val frontmatter = SkillFrontmatter(log)
    private val reportedLargeRoots = mutableSetOf<Path>()

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
            ?.let { frontmatter.readItem(SkillPlace(it, folder, root.path), name) }
            ?.takeUnless { it.name in disabled }
    }

    private fun command(child: Path, root: Path): LocalSkillItem? {
        val leaf = child.fileName.toString().takeIf { it.endsWith(COMMAND_EXT) } ?: return null
        val name = SkillName.valid(leaf.removeSuffix(COMMAND_EXT)) ?: return null
        return SkillPaths.file(child)?.let { frontmatter.readItem(SkillPlace(it, folder = null, root = root), name) }
    }

    private fun children(dir: Path): List<Path> = try {
        val entries = Files.list(dir).use { stream -> stream.limit(MAX_ENTRIES_PER_ROOT.toLong() + 1).toList() }
        if (entries.size > MAX_ENTRIES_PER_ROOT && reportedLargeRoots.add(dir)) {
            val root = LogSafe.str(dir.toString())
            log("[antigravity] skill discovery capped at $MAX_ENTRIES_PER_ROOT entries for $root\n")
        }
        entries.take(MAX_ENTRIES_PER_ROOT).sorted()
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
private const val MAX_ENTRIES_PER_ROOT = 1_000
private val NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
