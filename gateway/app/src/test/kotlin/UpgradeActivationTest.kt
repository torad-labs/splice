// The activation transaction of `splice upgrade` (v0.4.0, FEATURES.md §5) under a LATE failure — after
// the previous and current links moved — and under a failure of the restoration itself: the refusal is
// always the authored UpgradeRefused, the pointers are back when they can be, and when they cannot the
// message names what is inconsistent.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.LinkPointer
import splice.app.cli.UpgradeActivation
import splice.app.cli.UpgradeExit
import splice.app.cli.UpgradeLayout
import splice.app.cli.UpgradeProcess
import splice.app.cli.UpgradeRefused
import splice.app.cli.UpgradeWrapper
import splice.core.util.EnvReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class UpgradeActivationTest {

    private fun layout(home: Path): UpgradeLayout {
        val env = EnvReader { name ->
            when (name) {
                "SPLICE_SHARE_DIR" -> home.resolve("share").toString()
                "HOME" -> home.toString()
                else -> null
            }
        }
        val layout = UpgradeLayout(env)
        for (version in listOf("0.3.2", "9.9.9")) {
            val dir = Files.createDirectories(layout.versionDir(version))
            Files.writeString(dir.resolve("splice.jar"), "jar $version")
            Files.writeString(dir.resolve("splice-launch"), "shim $version")
        }
        Files.writeString(layout.liveShim, "shim 0.3.2")
        layout.point(layout.current, Path.of("0.3.2"))
        layout.point(layout.liveJar, Path.of("releases/0.3.2/splice.jar"))
        return layout
    }

    private fun wrapper() = UpgradeWrapper(UpgradeProcess { _, _ -> UpgradeExit(0, "") })

    private fun target(link: Path) = Files.readSymbolicLink(link).toString()

    @Test
    fun `a failure after the links moved puts every pointer back and refuses with the authored reason`(
        @TempDir home: Path,
    ) {
        val layout = layout(home)
        var liveFailed = false
        val failLive = LinkPointer { link, targetPath ->
            if (link == layout.liveJar && !liveFailed) {
                liveFailed = true
                throw IOException("disk full")
            }
            layout.point(link, targetPath)
        }
        val refused = assertThrows<UpgradeRefused> {
            UpgradeActivation(layout, wrapper(), failLive).activate("9.9.9", "0.3.2")
        }
        assertTrue(refused.reason.contains("0.3.2 restored"), refused.reason)
        assertEquals("0.3.2", target(layout.current), "current points back at the running release")
        assertFalse(Files.exists(layout.previous), "previous did not exist before and does not now")
        assertEquals("releases/0.3.2/splice.jar", target(layout.liveJar))
        assertEquals("shim 0.3.2", Files.readString(layout.liveShim), "the pristine shim is back")
    }

    @Test
    fun `a restoration that fails is reported by name instead of escaping as an exception`(@TempDir home: Path) {
        val layout = layout(home)
        var calls = 0
        val failFromLive = LinkPointer { link, targetPath ->
            calls += 1
            if (link == layout.liveJar || calls > 3) throw IOException("read-only")
            layout.point(link, targetPath)
        }
        val refused = assertThrows<UpgradeRefused> {
            UpgradeActivation(layout, wrapper(), failFromLive).activate("9.9.9", "0.3.2")
        }
        assertTrue(refused.reason.contains("recovery FAILED for"), refused.reason)
        assertTrue(refused.reason.contains("current"), "names the pointer it could not put back: ${refused.reason}")
        assertFalse(refused.reason.contains("restored"), refused.reason)
    }

    @Test
    fun `an activation that began without a live shim removes the one it wrote when it fails`(@TempDir home: Path) {
        val layout = layout(home)
        Files.delete(layout.liveShim)
        var liveFailed = false
        val failLive = LinkPointer { link, targetPath ->
            if (link == layout.liveJar && !liveFailed) {
                liveFailed = true
                throw IOException("disk full")
            }
            layout.point(link, targetPath)
        }
        val refused = assertThrows<UpgradeRefused> {
            UpgradeActivation(layout, wrapper(), failLive).activate("9.9.9", "0.3.2")
        }
        assertTrue(refused.reason.contains("0.3.2 restored"), refused.reason)
        assertFalse(Files.exists(layout.liveShim), "no shim before, no shim after: the new release's is not kept")
        assertEquals("0.3.2", target(layout.current))
    }
}
