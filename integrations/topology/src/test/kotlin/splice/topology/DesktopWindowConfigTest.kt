// splice.toml's `[desktop]` table (issue #791): the Code-tab windows parse as written onto the topology the
// daemon boots, and a window that cannot run refuses boot at its own key, every fault listed at once.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.desktop.DesktopConfig
import java.nio.file.Path

class DesktopWindowConfigTest {
    @TempDir lateinit var directory: Path

    private fun read(toml: String): ConfigRead = ConfigFindings.read(toml, directory)

    private fun refusedAt(toml: String): List<String> =
        assertInstanceOf(ConfigRead.Refused::class.java, read(toml)).findings.map { it.path }.sorted()

    @Test
    fun `no desktop table reads as Desktop's shipped windows`() {
        val ready = assertInstanceOf(ConfigRead.Ready::class.java, read("[daemon]\ncontrol_port = 3096\n"))
        assertEquals(DesktopConfig(codeWindow = 200_000, codeWindow1m = 1_000_000), ready.topology.desktop)
    }

    @Test
    fun `a desktop table parses as written, one key at a time`() {
        val both = assertInstanceOf(
            ConfigRead.Ready::class.java,
            read("[desktop]\ncode_window = 320000\ncode_window_1m = 640000\n"),
        )
        assertEquals(DesktopConfig(codeWindow = 320_000, codeWindow1m = 640_000), both.topology.desktop)
        val bigOnly = assertInstanceOf(ConfigRead.Ready::class.java, read("[desktop]\ncode_window_1m = 640000\n"))
        assertEquals(DesktopConfig(codeWindow = 200_000, codeWindow1m = 640_000), bigOnly.topology.desktop)
        // The same reading the loader's plain parse gives, which other readers boot from.
        assertEquals(640_000L, TopologyLoader.parse("[desktop]\ncode_window_1m = 640000\n").desktop.codeWindow1m)
    }

    @Test
    fun `a window of zero or below refuses boot at its key`() {
        assertEquals(listOf("desktop.code_window"), refusedAt("[desktop]\ncode_window = 0\n"))
        assertEquals(listOf("desktop.code_window"), refusedAt("[desktop]\ncode_window = -1\n"))
        assertEquals(listOf("desktop.code_window_1m"), refusedAt("[desktop]\ncode_window_1m = 0\n"))
    }

    @Test
    fun `a big window below the standard one refuses boot, defaults filling the silent key`() {
        assertEquals(
            listOf("desktop.code_window_1m"),
            refusedAt("[desktop]\ncode_window = 500000\ncode_window_1m = 400000\n"),
        )
        assertEquals(listOf("desktop.code_window_1m"), refusedAt("[desktop]\ncode_window = 2000000\n"))
        assertEquals(listOf("desktop.code_window_1m"), refusedAt("[desktop]\ncode_window_1m = 100000\n"))
    }

    @Test
    fun `a window that is no whole number refuses boot at its key`() {
        assertEquals(listOf("desktop.code_window"), refusedAt("[desktop]\ncode_window = \"320000\"\n"))
        assertEquals(listOf("desktop.code_window_1m"), refusedAt("[desktop]\ncode_window_1m = 640000.5\n"))
        assertEquals(listOf("desktop.code_window"), refusedAt("[desktop]\ncode_window = true\n"))
    }

    @Test
    fun `every fault in the table is listed at once, an unknown key among them`() {
        val toml = "[desktop]\ncode_window = 0\ncode_window_1m = \"wide\"\nchat_window = 100000\n"
        assertEquals(
            listOf("desktop.chat_window", "desktop.code_window", "desktop.code_window_1m"),
            refusedAt(toml),
        )
    }
}
