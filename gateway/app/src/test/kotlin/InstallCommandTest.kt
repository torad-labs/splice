// NEW: splice install/uninstall (P5-CLI) — argv[0] symlink creation from the topology, against a
// fake HOME. Proves: install links ~/.local/bin/<command> -> the shared launch shim, uses the
// per-head command name, is idempotent (re-link over an existing symlink), never clobbers a real
// file, and uninstall removes the links.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.install
import splice.app.cli.installedShimVersion
import splice.app.cli.shimStalenessWarning
import splice.app.cli.uninstall
import splice.core.SHIM_VERSION
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink

class InstallCommandTest {

    // Hermetic env: the first real CI run failed on the runner's ambient XDG_CONFIG_HOME steering
    // configPath away from the swapped user.home. Every command call pins env to nothing.
    private val noEnv: (String) -> String? = { null }

    private fun withHome(home: Path, block: () -> Unit) {
        val prev = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        try {
            block()
        } finally {
            System.setProperty("user.home", prev)
        }
    }

    private fun seedTopology(home: Path) {
        val cfg = home.resolve(".config").resolve("splice")
        Files.createDirectories(cfg)
        cfg.resolve("splice.toml").writeString(
            """
            [daemon]
            control_port = 3096

            [providers.codex]
            dialect = "openai-responses"
            base_url = "https://x"
            auth = { kind = "chatgpt-oauth" }

            [heads.claudex]
            provider = "codex"
            port = 3099
            discovery_prefix = "claude-codex--"
            pinned_model = "gpt-5.6-sol"

            [heads.claudex.claude]
            command = "claudex"

            [heads.grok]
            provider = "codex"
            port = 3100
            discovery_prefix = "claude-grok--"
            pinned_model = "gpt-5.6-sol"
            """.trimIndent(),
        )
        // the shared launch shim the symlinks point at
        val share = home.resolve(".local").resolve("share").resolve("splice")
        Files.createDirectories(share)
        share.resolve("splice-launch").writeString("#!/usr/bin/env bash\n")
    }

    @Test
    fun `install links each head command to the shared shim`(@TempDir home: Path) {
        withHome(home) {
            seedTopology(home)
            install("--all", env = noEnv)
            val bin = home.resolve(".local").resolve("bin")
            val claudex = bin.resolve("claudex")
            val grok = bin.resolve("grok")
            assertTrue(claudex.isSymbolicLink())
            assertTrue(grok.isSymbolicLink()) // head key when no command override
            assertTrue(claudex.readSymbolicLink().toString().endsWith("splice-launch"))
        }
    }

    @Test
    fun `install a single head only`(@TempDir home: Path) {
        withHome(home) {
            seedTopology(home)
            install("claudex", env = noEnv)
            val bin = home.resolve(".local").resolve("bin")
            assertTrue(bin.resolve("claudex").isSymbolicLink())
            assertFalse(Files.exists(bin.resolve("grok"), NOFOLLOW_LINKS))
        }
    }

    @Test
    fun `install is idempotent and fails loudly rather than clobber a real file`(@TempDir home: Path) {
        withHome(home) {
            seedTopology(home)
            install("claudex", env = noEnv)
            install("claudex", env = noEnv) // re-run: replaces the symlink, no error
            assertTrue(home.resolve(".local/bin/claudex").isSymbolicLink())
            // A real file makes the whole install fail so install.sh cannot print false success.
            val bin = home.resolve(".local").resolve("bin")
            bin.resolve("grok").writeString("real file")
            assertThrows<IllegalStateException> { install("grok", env = noEnv) }
            assertFalse(bin.resolve("grok").isSymbolicLink())
            assertEquals("real file", Files.readString(bin.resolve("grok")))
        }
    }

    @Test
    fun `install preflights every link before changing any command`(@TempDir home: Path) {
        withHome(home) {
            seedTopology(home)
            val bin = home.resolve(".local").resolve("bin")
            Files.createDirectories(bin)
            bin.resolve("grok").writeString("real file")

            assertThrows<IllegalStateException> { install("--all", env = noEnv) }

            assertFalse(Files.exists(bin.resolve("claudex"), NOFOLLOW_LINKS))
            assertFalse(Files.exists(bin.resolve("splice"), NOFOLLOW_LINKS))
            assertEquals("real file", Files.readString(bin.resolve("grok")))
        }
    }

    @Test
    fun `install refuses to create dangling wrappers when the shared shim is missing`(@TempDir home: Path) {
        withHome(home) {
            seedTopology(home)
            Files.delete(shimPath(home))

            assertThrows<IllegalStateException> { install("--all", env = noEnv) }

            assertFalse(Files.exists(home.resolve(".local/bin/claudex"), NOFOLLOW_LINKS))
        }
    }

    @Test
    fun `uninstall removes the links`(@TempDir home: Path) {
        withHome(home) {
            seedTopology(home)
            install("--all", env = noEnv)
            uninstall("--all", env = noEnv)
            val bin = home.resolve(".local").resolve("bin")
            assertFalse(Files.exists(bin.resolve("claudex"), NOFOLLOW_LINKS))
            assertFalse(Files.exists(bin.resolve("grok"), NOFOLLOW_LINKS))
        }
    }

    private fun shimPath(home: Path): Path =
        home.resolve(".local").resolve("share").resolve("splice").resolve("splice-launch")

    private fun writeShim(home: Path, contents: String) {
        val shim = shimPath(home)
        Files.createDirectories(shim.parent)
        shim.writeString(contents)
    }

    @Test
    fun `installedShimVersion returns null when no shim is installed`(@TempDir home: Path) {
        withHome(home) {
            assertNull(installedShimVersion(env = noEnv))
        }
    }

    @Test
    fun `installedShimVersion extracts the SPLICE_SHIM_VERSION marker`(@TempDir home: Path) {
        withHome(home) {
            writeShim(
                home,
                """
                #!/usr/bin/env bash
                set -euo pipefail
                SPLICE_SHIM_VERSION="shim-1"
                echo hi
                """.trimIndent(),
            )
            assertEquals("shim-1", installedShimVersion(env = noEnv))
        }
    }

    @Test
    fun `shimStalenessWarning is null when the marker matches SHIM_VERSION`(@TempDir home: Path) {
        withHome(home) {
            writeShim(home, "#!/usr/bin/env bash\nSPLICE_SHIM_VERSION=\"$SHIM_VERSION\"\n")
            assertNull(shimStalenessWarning(env = noEnv))
        }
    }

    @Test
    fun `shimStalenessWarning warns when the marker is stale or missing`(@TempDir home: Path) {
        withHome(home) {
            writeShim(home, "#!/usr/bin/env bash\nSPLICE_SHIM_VERSION=\"shim-0\"\n")
            val stale = shimStalenessWarning(env = noEnv)
            assertTrue(stale != null && stale.contains("STALE") && stale.contains("splice install"))
        }
        withHome(home) {
            writeShim(home, "#!/usr/bin/env bash\necho no marker here\n")
            val missing = shimStalenessWarning(env = noEnv)
            assertTrue(missing != null && missing.contains("STALE") && missing.contains("splice install"))
        }
    }

    @Test
    fun `shimStalenessWarning is null when no shim file exists at all`(@TempDir home: Path) {
        withHome(home) {
            assertNull(shimStalenessWarning(env = noEnv))
        }
    }
}

private fun Path.writeString(s: String) {
    Files.writeString(this, s)
}
