// UpgradeLayout.versionDir (v0.4.0, FEATURES.md §5) admits exactly normalized SemVer 2.0.0 — the one
// grammar that keeps a release directory name a single, canonical path segment.
package splice.app.cli.upgrade

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class UpgradeLayoutTest {

    private fun layout(home: Path) = UpgradeLayout(
        EnvReader { name -> if (name == "SPLICE_SHARE_DIR") home.resolve("share").toString() else null },
    )

    /** Another upgrade's staging directory is its work in progress, not debris (review 2026-09-14). */
    @Test
    fun `a live upgrade's staging directory is never prunable, a dead one is`(@TempDir home: Path) {
        val layout = layout(home)
        val live = Files.createDirectories(layout.releases.resolve(".staging-${ProcessHandle.current().pid()}"))
        val dead = Files.createDirectories(layout.releases.resolve(".staging-2147483647"))
        val debris = Files.createDirectories(layout.releases.resolve("0.0.1"))
        assertEquals(setOf(dead, debris), layout.prunable().toSet())
        assertTrue(Files.isDirectory(live))
    }

    @Test
    fun `valid SemVer resolves one segment below releases`(@TempDir home: Path) {
        val layout = layout(home)
        val good = listOf("1.2.3", "0.3.0-beta.1", "1.2.3-rc-1.x.7") +
            listOf("1.2.3-alpha-hyphen", "1.2.3+build.5", "1.0.0-0.3.7")
        for (v in good) {
            assertEquals(layout.releases.resolve(v), layout.versionDir(v), v)
        }
    }

    @Test
    fun `leading zeros, empty identifiers, link names and paths are refused before a path exists`(@TempDir home: Path) {
        val layout = layout(home)
        val bad = listOf("01.2.3", "1.02.3", "1.2.3-01", "1.2.3-alpha..beta", "1.2.3-", "1.2.3+", "1.2", "v1.2.3")
        for (v in bad + listOf("current", "previous", "..", "../x", "/tmp/x", "1.2.3/x", "1.2.3 ")) {
            val refused = assertThrows<UpgradeRefused>(v) { layout.versionDir(v) }
            assertTrue(refused.reason.contains("not a normalized SemVer"), refused.reason)
        }
    }
}
