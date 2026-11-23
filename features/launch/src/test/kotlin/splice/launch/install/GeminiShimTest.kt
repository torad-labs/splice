// The Antigravity shim (install.sh step 4a'): `~/.local/lib/splice/gemini/claude`, which Claude Desktop runs in place
// of Claude Code for an Antigravity model. `splice install --all` and an upgrade's aftercare lay the bytes install.sh
// lays, and only when the file differs.
package splice.launch.install

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.UserHome
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class GeminiShimTest {

    @Test
    fun `a missing shim is laid with install-sh's bytes, executable, and a second lay changes nothing`(
        @TempDir home: Path,
    ) {
        val bin = home.resolve("bin")
        UserHome.within(home) {
            val shim = GeminiShim(env(bin))
            assertFalse(shim.current())
            val laid = shim.lay()
            val path = home.resolve(".local/lib/splice/gemini/claude")
            assertEquals(GeminiShimLaid.Written(path), laid)
            assertEquals("#!/bin/sh\nexec '${bin.resolve("splice")}' desktop acp \"\$@\"\n", Files.readString(path))
            assertTrue(Files.isExecutable(path))
            assertTrue(shim.current())
            assertEquals(GeminiShimLaid.Current, shim.lay())
        }
    }

    @Test
    fun `a shim naming another splice is laid again for this one`(@TempDir home: Path) {
        UserHome.within(home) {
            val path = Files.createDirectories(home.resolve(".local/lib/splice/gemini")).resolve("claude")
            Files.writeString(path, "#!/bin/sh\nexec '/old/bin/splice' desktop acp \"\$@\"\n")
            val shim = GeminiShim(env(home.resolve("bin")))
            assertFalse(shim.current())
            assertEquals(GeminiShimLaid.Written(path), shim.lay())
            assertTrue(Files.readString(path).contains(home.resolve("bin/splice").toString()))
        }
    }

    private fun env(bin: Path) = EnvReader { name -> bin.toString().takeIf { name == "SPLICE_BIN_DIR" } }
}
