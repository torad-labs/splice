// NEW: Oct 12, 2026 — the Projects folder knob as Settings, the TOML and the environment save it: typed paths are
// checked at Save, spelled as typed, and a leading ~ is the home.
package splice.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProjectFoldersConfigTest {
    @TempDir
    lateinit var tmp: Path

    private fun service(
        env: Map<String, String> = emptyMap(),
        overrides: Map<String, String> = emptyMap(),
    ): ConfigService = ConfigService(
        StatePaths(baseOverride = tmp.resolve("state")),
        headOverrides = overrides,
        envReader = { env[it] },
    )

    @Test
    fun `a Projects folder typed into Settings that is not a folder is refused at Save, naming its entry`() {
        val real = tmp.resolve("work").also { Files.createDirectories(it) }
        val svc = service()
        val result = svc.patch(mapOf("projectsFolders" to "$real:${tmp.resolve("gone")}"))
        assertEquals(mapOf("projectsFolders" to "entry 2 is not a folder this process can read"), result.rejected)
        assertEquals(emptyList<String>(), svc.getConfig().projectsFolders)
        val saved = svc.patch(mapOf("projectsFolders" to real.toString()))
        assertEquals(listOf(real.toString()), saved.effective.projectsFolders)
    }

    @Test
    fun `a Projects folder typed with a tilde is saved as typed and found under the home`() {
        val home = Files.createDirectories(tmp.resolve("home"))
        Files.createDirectories(home.resolve("work"))
        UserHome.within(home) {
            val saved = service().patch(mapOf("projectsFolders" to "~/work"))
            assertEquals(emptyMap<String, String>(), saved.rejected)
            assertEquals(listOf("~/work"), saved.effective.projectsFolders)
            assertEquals(true, ProjectFolderCheck.found("~/work"))
            assertEquals(false, ProjectFolderCheck.found("~/nowhere"))
        }
    }

    @Test
    fun `projectsFolders parses colon-separated absolute folders and refuses a relative entry by name`() {
        assertEquals(emptyList<String>(), service().getConfig().projectsFolders)
        val cfg = service(env = mapOf("SPLICE_PROJECTS_FOLDERS" to "/a/projects:/a/infra")).getConfig()
        assertEquals(listOf("/a/projects", "/a/infra"), cfg.projectsFolders)
        val refused = service(overrides = mapOf("projectsFolders" to "/a/projects:relative")).coerceRejects()
        assertEquals(setOf("projectsFolders"), refused.keys)
    }
}
