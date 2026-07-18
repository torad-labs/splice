// NEW: splice install/uninstall (P5-CLI) — argv[0] symlink creation from the topology, against a
// fake HOME. Proves: install links ~/.local/bin/<command> -> the shared launch shim, uses the
// per-head command name, is idempotent (re-link over an existing symlink), never clobbers a real
// file, and uninstall removes the links.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.install
import splice.app.cli.uninstall
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink

class InstallCommandTest {

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
            install("--all")
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
            install("claudex")
            val bin = home.resolve(".local").resolve("bin")
            assertTrue(bin.resolve("claudex").isSymbolicLink())
            assertFalse(Files.exists(bin.resolve("grok"), NOFOLLOW_LINKS))
        }
    }

    @Test
    fun `install is idempotent and never clobbers a real file`(@TempDir home: Path) {
        withHome(home) {
            seedTopology(home)
            install("claudex")
            install("claudex") // re-run: replaces the symlink, no error
            assertTrue(home.resolve(".local/bin/claudex").isSymbolicLink())
            // a REAL file where the link would go is left alone
            val bin = home.resolve(".local").resolve("bin")
            bin.resolve("grok").writeString("real file")
            install("grok")
            assertFalse(bin.resolve("grok").isSymbolicLink())
            assertEquals("real file", Files.readString(bin.resolve("grok")))
        }
    }

    @Test
    fun `uninstall removes the links`(@TempDir home: Path) {
        withHome(home) {
            seedTopology(home)
            install("--all")
            uninstall("--all")
            val bin = home.resolve(".local").resolve("bin")
            assertFalse(Files.exists(bin.resolve("claudex"), NOFOLLOW_LINKS))
            assertFalse(Files.exists(bin.resolve("grok"), NOFOLLOW_LINKS))
        }
    }
}

private fun Path.writeString(s: String) {
    Files.writeString(this, s)
}
