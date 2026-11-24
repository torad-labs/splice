// NEW: terminal launch profiles (issue #791, comment §4). The folder a profile's session runs in, checked before
// anything is written there: it is never `~/.claude`, inside it, or a folder holding it, read through every link on
// the way (a `dir` that is a link to `~/.claude` is `~/.claude`); and a folder that already holds files is one splice
// made for this very profile, by the mark it leaves naming it, so a `dir` naming someone's own folder, or another
// profile's, is never written over.
package splice.client.profile

import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class ProfileFolder(private val claudeDir: Path) {
    /** [folder] made ready for profile [name]: created owner-only when missing and marked; null when it is ready, or
     *  why it can't be the profile's. */
    fun prepare(folder: Path, name: String): String? {
        val refusal = clash(folder) ?: foreign(folder, name)
        if (refusal == null) {
            Files.createDirectories(folder)
            SecureFile.ownerOnlyDirectory(folder)?.let { throw IOException("$folder can't be made owner-only: $it") }
            if (markedFor(folder) != name) SecureFile.writeAtomic0600(folder.resolve(PROFILE_MARK), "$MARK_TEXT$name\n")
        }
        return refusal
    }

    /** Why [folder] is `~/.claude`'s, read through links; null when it is not. */
    private fun clash(folder: Path): String? {
        val claude = real(claudeDir)
        val where = real(folder)
        val near = where.startsWith(claude) || claude.startsWith(where)
        return "$folder is ~/.claude, inside it or holds it, and a profile never writes there".takeIf { near }
    }

    /** Why [folder] is not profile [name]'s to rebuild: not a folder, another profile's by its mark, or holding files
     *  without a mark; null when it is free or already [name]'s. */
    private fun foreign(folder: Path, name: String): String? {
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isDirectory(folder)) return "$folder is not a folder"
        val owner = markedFor(folder)
        val free = owner == null && Files.list(folder).use { !it.findAny().isPresent }
        return when {
            owner == name || free -> null
            owner != null -> "$folder is profile $owner's folder; give profile $name a dir of its own"
            else -> "$folder holds files splice did not put there; empty it or name another dir for the profile"
        }
    }

    /** The profile [folder]'s mark names; null with no mark. */
    private fun markedFor(folder: Path): String? {
        val mark = folder.resolve(PROFILE_MARK)
        if (!Files.isRegularFile(mark, LinkOption.NOFOLLOW_LINKS)) return null
        return Files.readString(mark).trim().removePrefix(MARK_TEXT).trim()
    }

    /** [path] with every link on the way read, as far as it exists; the rest as written. */
    private fun real(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var existing: Path? = absolute
        while (existing != null && !Files.exists(existing)) existing = existing.parent
        val base = existing ?: return absolute
        return base.toRealPath().resolve(base.relativize(absolute)).normalize()
    }
}

/** The file that marks a folder as one splice rebuilds for a profile. */
internal const val PROFILE_MARK: String = ".splice-profile"

private const val MARK_TEXT = "splice rebuilds this folder before each `splice launch claude --profile` of: "
