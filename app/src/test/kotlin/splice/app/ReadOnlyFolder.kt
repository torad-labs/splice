// A folder made read-only refuses writes only to a user its permission bits bind. Root writes through them, so a test
// of what splice does when the disk refuses a write can't make the disk refuse where it runs as root (a build in a
// container, or a build machine that logs in as root). Such a test checks first, and is skipped with this reason where
// the folder still takes writes.
package splice.app

import org.junit.jupiter.api.Assumptions.assumeFalse
import java.nio.file.Files
import java.nio.file.Path

internal object ReadOnlyFolder {
    /** Skips the running test unless [folder], already made read-only, now refuses this user's writes. */
    fun assumeRefuses(folder: Path) {
        assumeFalse(
            Files.isWritable(folder),
            "this user writes through a read-only folder (root does), so the disk can't be made to refuse a write here",
        )
    }
}
