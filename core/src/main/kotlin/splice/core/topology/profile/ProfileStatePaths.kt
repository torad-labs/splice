package splice.core.topology.profile

import splice.core.config.UserHome
import splice.core.util.PathProbe
import java.nio.file.Files
import java.nio.file.Path

/** Which paths can seed a profile, shared by config validation and launch. A missing source is allowed; a path
 *  into state splice manages or into Gemini's folder is not, even through a symlink. */
public class ProfileStatePaths(
    private val home: Path,
    defaultRoot: Path,
    headDirs: List<Path>,
    folder: Path,
) {
    // A head sharing ~/.claude uses the person's own state, not a copy.
    private val claude = root(home.resolve(UserHome.CLAUDE_DIR))
    private val protectedDirs = listOf(folder, defaultRoot, home.resolve(GEMINI_FOLDER))
    private val excludedRoots = (protectedDirs + headDirs.filterNot { root(it) == claude })
        .flatMap { listOf(it.toAbsolutePath().normalize(), root(it)) }.toSet()

    /** Resolve an explicit source, whose spelling must be absolute or start with `~/`. */
    public fun named(raw: String): Path? =
        raw.takeUnless { it.isBlank() || it == "~" }?.let { allowed(UserHome.declared(it, home)) }

    /** Resolve an eligible source, or null when [file] does not name personal state. */
    public fun allowed(file: Path?): Path? {
        if (file == null || !file.isAbsolute) return null
        val entry = PathProbe.realEntry(file)
        val real = PathProbe.resolved(file)
        val target = real ?: PathProbe.throughLinks(file)
        if (entry == null || target == null) return null
        // A missing component before '..' can make the literal path unreadable even if its projected target exists.
        return (real ?: file).takeUnless { listOf(file.normalize(), entry, target).any(::excluded) }
    }

    private fun excluded(path: Path): Boolean =
        excludedRoots.any(path::startsWith) ||
            generateSequence(path.parent) { it.parent }.any { Files.exists(it.resolve(PROFILE_MARK)) }

    private fun root(path: Path): Path = PathProbe.throughLinks(path) ?: path.toAbsolutePath().normalize()
}

/** The file that marks a folder as one splice rebuilds for a profile. */
public const val PROFILE_MARK: String = ".splice-profile"

/** What an explicit source must name at both config validation and launch. */
public const val PROFILE_STATE_PATH_RULE: String =
    "must name an absolute file or start with ~/ and resolve outside profile, head and ~/.gemini folders"

internal const val GEMINI_FOLDER: String = ".gemini"
