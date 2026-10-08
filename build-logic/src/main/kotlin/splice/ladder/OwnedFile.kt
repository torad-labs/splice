// The one file a gate leg owns (a ladder row's `owns`), removed as the leg starts so a rerun never finds its own previous
// output. Pure Kotlin, so the rule is proven against a @TempDir (OwnedFileTest); the gate-ladder plugin is the caller.
package splice.ladder

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** The file a leg owns. [release] removes that one file and nothing else: a directory at the path is refused by name,
 *  and a delete that fails throws, so the leg never runs over a path it did not clear. */
class OwnedFile(private val path: Path) {
    fun release() {
        require(!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "$path is a directory: a gate leg owns one file, and it never removes a directory"
        }
        Files.deleteIfExists(path)
    }
}
