// NEW: terminal launch profiles (issue #791, comment §4). A folder inside a profile taken away whole before it is
// rebuilt: each link is removed as a link and never followed, so nothing it points at (the person's plugins in
// `~/.claude`) is ever reached; and the walk is bounded, so a folder that is not what it seems stops the launch with a
// reason rather than holding it up.
package splice.client.profile

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal class ProfileTree(private val maxEntries: Int = PROFILE_MAX_ENTRIES) {
    /** [dir] and everything under it removed; nothing when it is not there. A link at [dir] is removed as a link. */
    fun clear(dir: Path) {
        if (Files.isSymbolicLink(dir) || Files.isRegularFile(dir)) return Files.delete(dir)
        if (!Files.isDirectory(dir)) return
        var seen = 0
        Files.walkFileTree(
            dir,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    seen++
                    if (seen > maxEntries) throw IOException("$dir holds more than $maxEntries entries; not cleared")
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(folder: Path, failure: IOException?): FileVisitResult {
                    failure?.let { throw it }
                    Files.delete(folder)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
