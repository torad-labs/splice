package splice.lifecycle.release

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.deleteRecursively

// SemVer 2.0.0 (semver.org): no leading zeros in numeric parts or numeric prerelease identifiers.
private const val NUM = """(?:0|[1-9]\d*)"""
private const val PRE_ID = """(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)"""
private const val SEMVER_SEGMENT =
    """$NUM\.$NUM\.$NUM(?:-$PRE_ID(?:\.$PRE_ID)*)?(?:\+[0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*)?"""

/** Versioned installs share one path grammar and never follow links when deleting directories. */
internal object ReleaseDirectories {
    private val semver = Regex(SEMVER_SEGMENT)

    fun isVersion(version: String): Boolean = semver.matches(version)

    fun children(root: Path): List<Path> =
        if (Files.isDirectory(root)) {
            Files.list(root).use { entries ->
                entries.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.toList()
            }
        } else {
            emptyList()
        }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun discard(path: Path) {
        if (Files.exists(path)) path.deleteRecursively()
    }
}
