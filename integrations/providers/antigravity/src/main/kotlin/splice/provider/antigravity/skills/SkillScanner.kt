package splice.provider.antigravity.skills

import splice.core.util.LogSafe
import splice.core.util.LogSink
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path

/** A folder of skills: its [path] as declared, the names [disabled] in it, and the [project] it belongs to, or null for
 *  a folder that is the person's own. */
internal data class SkillRoot(val path: Path, val disabled: Set<String> = emptySet(), val project: Path? = null) {
    /** The path agy's skill index names the skill at [entry] by, whose real folder is [folder] and real file [file];
     *  null when the index may not name it. Trust follows who wrote the folder, not where a link lands.
     *
     *  In a folder that is the person's own, every skill is theirs, links included (a toolkit, stow or a dotfile
     *  manager installs skills as links), and it is named where they put it, `<path>/<entry>/SKILL.md`: no link's
     *  target goes upstream, and agy's read follows the link to what is installed now, as Claude Code's would.
     *
     *  In a project's folder, a skill is named only when its real folder and file lie inside [project], and by that
     *  real file: a link the project wrote can point neither the index nor a later read the model starts at a file
     *  outside the project. */
    fun indexPath(entry: Path, folder: Path, file: Path): Path? {
        val boundary = project ?: return path.resolve(entry.fileName).resolve(SKILL_DOC)
        return file.takeIf { folder.startsWith(boundary) && it.startsWith(boundary) }
    }
}

internal class SkillScanner(private val log: LogSink) {
    private val frontmatter = SkillFrontmatter()
    private val reportedLargeRoots = mutableSetOf<Path>()

    fun skills(roots: List<SkillRoot>): Sequence<LocalSkillItem> =
        roots.asSequence()
            .mapNotNull { root -> SkillPaths.directory(root.path)?.let { root to it } }
            .distinctBy { (_, dir) -> dir }
            .flatMap { (root, dir) -> children(dir).asSequence().mapNotNull { skill(it, root) } }

    fun commands(roots: List<Path>): Sequence<LocalSkillItem> =
        roots.asSequence()
            .mapNotNull { root -> SkillPaths.directory(root)?.let { root to it } }
            .distinctBy { (_, dir) -> dir }
            .flatMap { (root, dir) -> children(dir).asSequence().mapNotNull { command(it, root) } }

    private fun skill(child: Path, root: SkillRoot): LocalSkillItem? {
        val disabled = root.disabled
        val name = SkillName.valid(child.fileName.toString())?.takeUnless(disabled::contains) ?: return null
        val folder = SkillPaths.directory(child) ?: return null
        return SkillPaths.file(folder.resolve(SKILL_DOC))
            ?.let { file -> SkillPlace(file, folder, root.path, root.indexPath(child, folder, file)) }
            ?.let { frontmatter.readItem(it, name) }
            ?.takeUnless { it.name in disabled }
    }

    private fun command(child: Path, root: Path): LocalSkillItem? {
        val leaf = child.fileName.toString().takeIf { it.endsWith(COMMAND_EXT) } ?: return null
        val name = SkillName.valid(leaf.removeSuffix(COMMAND_EXT)) ?: return null
        return SkillPaths.file(child)?.let { frontmatter.readItem(SkillPlace(it, null, root, indexPath = null), name) }
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
