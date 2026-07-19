// NEW: OSS-D R5 — `splice install` and install.sh must agree on SPLICE_BIN_DIR/SPLICE_SHARE_DIR.
// Env is faked via the injected reader seam (JVM cannot setenv).
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.config.InstallPaths
import java.nio.file.Paths

class InstallPathsTest {

    @Test
    fun `env overrides win over the home defaults`() {
        val env = mapOf("SPLICE_BIN_DIR" to "/opt/bin", "SPLICE_SHARE_DIR" to "/opt/share/splice")
        val paths = InstallPaths(envReader = { env[it] })
        assertEquals(Paths.get("/opt/bin"), paths.binDir)
        assertEquals(Paths.get("/opt/share/splice"), paths.shareDir)
    }

    @Test
    fun `defaults land under the user home when env is empty`() {
        val paths = InstallPaths(envReader = { null })
        assertTrue(paths.binDir.endsWith(Paths.get(".local", "bin")))
        assertTrue(paths.shareDir.endsWith(Paths.get(".local", "share", "splice")))
    }

    @Test
    fun `explicit overrides win over env`() {
        val env = mapOf("SPLICE_BIN_DIR" to "/opt/bin")
        val paths = InstallPaths(
            binOverride = Paths.get("/pinned/bin"),
            shareOverride = Paths.get("/pinned/share"),
            envReader = { env[it] },
        )
        assertEquals(Paths.get("/pinned/bin"), paths.binDir)
        assertEquals(Paths.get("/pinned/share"), paths.shareDir)
    }
}
