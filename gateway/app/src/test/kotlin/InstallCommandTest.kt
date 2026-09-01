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
import splice.app.cli.InstallCommand
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
            InstallCommand().install("--all", env = noEnv)
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
            InstallCommand().install("claudex", env = noEnv)
            val bin = home.resolve(".local").resolve("bin")
            assertTrue(bin.resolve("claudex").isSymbolicLink())
            assertFalse(Files.exists(bin.resolve("grok"), NOFOLLOW_LINKS))
        }
    }

    @Test
    fun `install is idempotent and fails loudly rather than clobber a real file`(@TempDir home: Path) {
        withHome(home) {
            seedTopology(home)
            InstallCommand().install("claudex", env = noEnv)
            InstallCommand().install("claudex", env = noEnv) // re-run: replaces the symlink, no error
            assertTrue(home.resolve(".local/bin/claudex").isSymbolicLink())
            // A real file makes the whole install fail so install.sh cannot print false success.
            val bin = home.resolve(".local").resolve("bin")
            bin.resolve("grok").writeString("real file")
            assertThrows<IllegalStateException> { InstallCommand().install("grok", env = noEnv) }
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

            assertThrows<IllegalStateException> { InstallCommand().install("--all", env = noEnv) }

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

            assertThrows<IllegalStateException> { InstallCommand().install("--all", env = noEnv) }

            assertFalse(Files.exists(home.resolve(".local/bin/claudex"), NOFOLLOW_LINKS))
        }
    }

    @Test
    fun `uninstall removes the links`(@TempDir home: Path) {
        withHome(home) {
            seedTopology(home)
            InstallCommand().install("--all", env = noEnv)
            InstallCommand().uninstall("--all", env = noEnv)
            val bin = home.resolve(".local").resolve("bin")
            assertFalse(Files.exists(bin.resolve("claudex"), NOFOLLOW_LINKS))
            assertFalse(Files.exists(bin.resolve("grok"), NOFOLLOW_LINKS))
            assertFalse(Files.exists(bin.resolve("splice"), NOFOLLOW_LINKS))
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
            assertNull(InstallCommand().installedShimVersion(env = noEnv))
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
            assertEquals("shim-1", InstallCommand().installedShimVersion(env = noEnv))
        }
    }

    @Test
    fun `shimStalenessWarning is null when the marker matches SHIM_VERSION`(@TempDir home: Path) {
        withHome(home) {
            writeShim(home, "#!/usr/bin/env bash\nSPLICE_SHIM_VERSION=\"$SHIM_VERSION\"\n")
            assertNull(InstallCommand().shimStalenessWarning(env = noEnv))
        }
    }

    @Test
    fun `shimStalenessWarning warns when the marker is stale or missing`(@TempDir home: Path) {
        withHome(home) {
            writeShim(home, "#!/usr/bin/env bash\nSPLICE_SHIM_VERSION=\"shim-0\"\n")
            val stale = InstallCommand().shimStalenessWarning(env = noEnv)
            assertTrue(stale != null && stale.contains("STALE") && stale.contains("splice install"))
        }
        withHome(home) {
            writeShim(home, "#!/usr/bin/env bash\necho no marker here\n")
            val missing = InstallCommand().shimStalenessWarning(env = noEnv)
            assertTrue(missing != null && missing.contains("STALE") && missing.contains("splice install"))
        }
    }

    @Test
    fun `shimStalenessWarning is null when no shim file exists at all`(@TempDir home: Path) {
        withHome(home) {
            assertNull(InstallCommand().shimStalenessWarning(env = noEnv))
        }
    }
}

private fun Path.writeString(s: String) {
    Files.writeString(this, s)
}

// DR-67 in its own class: the precheck cannot see a file that appears between it and the claim.
// The WrapperClaim seam interleaves that creator deterministically on the production path
// (SymlinkOp precedent): the exclusive claim must LOSE loud and never eat the foreign file —
// the old staged ATOMIC_MOVE + REPLACE_EXISTING replaced whatever sat at the name by move time.
class InstallLinkerClaimTest {

    private val noEnv: (String) -> String? = { null }

    private fun withHome(home: java.nio.file.Path, block: () -> Unit) {
        val prev = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        try {
            block()
        } finally {
            System.setProperty("user.home", prev)
        }
    }

    @Test
    fun `a foreign file appearing between check and claim wins - DR-67`(@TempDir home: Path) = withHome(home) {
        val share = home.resolve(".local").resolve("share").resolve("splice")
        Files.createDirectories(share)
        share.resolve("splice-launch").writeString("#!/usr/bin/env bash\n")
        val link = home.resolve(".local").resolve("bin").resolve("splice")
        val foreign = "#!/bin/sh # operator wrapper - must survive\n"
        val interleaving = splice.app.cli.WrapperClaim { l, target ->
            Files.createDirectories(l.parent)
            l.writeString(foreign)
            splice.app.cli.ExclusiveSymlinkClaim(l, target)
        }
        val linker = splice.app.cli.InstallLinker(claim = interleaving)
        org.junit.jupiter.api.assertThrows<IllegalStateException> { linker.installSelf(noEnv) }
        assertEquals(foreign, Files.readString(link), "the foreign wrapper must survive the lost claim")
        assertFalse(link.isSymbolicLink(), "the name must not have been retargeted")
    }

    @Test
    fun `an existing wrapper symlink is still repointed - DR-67 control`(@TempDir home: Path) = withHome(home) {
        val share = home.resolve(".local").resolve("share").resolve("splice")
        Files.createDirectories(share)
        share.resolve("splice-launch").writeString("#!/usr/bin/env bash\n")
        val bin = home.resolve(".local").resolve("bin")
        Files.createDirectories(bin)
        val stale = home.resolve("stale-target")
        stale.writeString("stale")
        Files.createSymbolicLink(bin.resolve("splice"), stale)
        assertTrue(splice.app.cli.InstallLinker().installSelf(noEnv))
        assertTrue(bin.resolve("splice").readSymbolicLink().toString().endsWith("splice-launch"))
    }

    // DR-84 (batches 6+7 review): the confirmed-symlink delete ran OUTSIDE the try, so a failed
    // claim (concurrent install, ENOSPC, read-only remount) destroyed a live wrapper and left
    // NOTHING at the command name — the old staged code's worst case was an un-updated wrapper.
    // A failed claim must put the previous target back; a foreign creator that won the window
    // keeps its file (DR-67's law outranks the restore).
    @Test
    fun `a failed claim restores the previous wrapper - DR-84`(@TempDir home: Path) = withHome(home) {
        val share = home.resolve(".local").resolve("share").resolve("splice")
        Files.createDirectories(share)
        share.resolve("splice-launch").writeString("#!/usr/bin/env bash\n")
        val bin = home.resolve(".local").resolve("bin")
        Files.createDirectories(bin)
        val previous = home.resolve("working-target")
        previous.writeString("working")
        Files.createSymbolicLink(bin.resolve("splice"), previous)
        val failing = splice.app.cli.WrapperClaim { _, _ -> throw java.io.IOException("disk full") }
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            splice.app.cli.InstallLinker(claim = failing).installSelf(noEnv)
        }
        assertTrue(bin.resolve("splice").isSymbolicLink(), "the command name must not be left empty")
        assertEquals(previous, bin.resolve("splice").readSymbolicLink(), "the working wrapper is restored")
    }
}

// DR-74 (invariant audit): the shim pre-flight used bare Files.exists(), which reads a dangling
// link, an untraversable parent, and an inaccessible shim all as "not installed" — telling the
// operator to reinstall through what is actually a permissions problem.
class InstallShimPresenceTest {

    private fun layoutEnv(tmp: java.nio.file.Path) = splice.core.util.EnvReader { name ->
        mapOf(
            "SPLICE_SHARE_DIR" to tmp.resolve("share").toString(),
            "SPLICE_BIN_DIR" to tmp.resolve("bin").toString(),
        )[name]
    }

    @Test
    fun `an unreadable shim aborts naming access, not reinstall - DR-74`(@TempDir tmp: java.nio.file.Path) {
        val share = Files.createDirectories(tmp.resolve("share"))
        Files.writeString(share.resolve("splice-launch"), "#!/bin/sh\n")
        val denied = java.nio.file.attribute.PosixFilePermissions.fromString("---------")
        val restored = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
        Files.setPosixFilePermissions(share, denied)
        val failure = try {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                splice.app.cli.InstallLinker().installSelf(layoutEnv(tmp))
            }
        } finally {
            Files.setPosixFilePermissions(share, restored)
        }
        assertTrue(failure.message!!.contains("fix access"), failure.message)
    }

    @Test
    fun `a genuinely missing shim keeps the install-sh remedy - DR-74 control`(@TempDir tmp: java.nio.file.Path) {
        val failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            splice.app.cli.InstallLinker().installSelf(layoutEnv(tmp))
        }
        assertTrue(failure.message!!.contains("run install.sh"), failure.message)
    }

    // DR-85 (batches 6+7 review): a DANGLING splice-launch stats as NoSuch while its NOFOLLOW
    // entry exists — the two-way classification called that "unreadable — fix access, not
    // reinstall", forbidding exactly the reinstall a dangling link needs. Third state.
    @Test
    fun `a dangling shim names the dangling state and the reinstall remedy - DR-85`(@TempDir tmp: java.nio.file.Path) {
        val share = Files.createDirectories(tmp.resolve("share"))
        Files.createSymbolicLink(share.resolve("splice-launch"), share.resolve("gone-target"))
        val failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            splice.app.cli.InstallLinker().installSelf(layoutEnv(tmp))
        }
        assertTrue(failure.message!!.contains("dangling"), failure.message)
        assertTrue(failure.message!!.contains("install.sh"), failure.message)
    }
}

// DR-101: `uninstall --all` with a topology it cannot read must never silently shrink to the
// self-link — pre-fix the fallback was listOfNotNull("--all"), so head wrappers stayed installed
// while the command exited 0 saying nothing. Corrupt config: refuse loudly, nonzero. Genuinely
// ABSENT config (NoSuchFileException — the positive absence evidence): self-link only, but SAID.
class UninstallUnreadableTopologyTest {

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

    private fun capture(block: () -> Boolean): Triple<Boolean, String, String> {
        val outBuf = java.io.ByteArrayOutputStream()
        val errBuf = java.io.ByteArrayOutputStream()
        val out = System.out
        val err = System.err
        System.setOut(java.io.PrintStream(outBuf, true))
        System.setErr(java.io.PrintStream(errBuf, true))
        return try {
            Triple(block(), outBuf.toString(), errBuf.toString())
        } finally {
            System.setOut(out)
            System.setErr(err)
        }
    }

    private fun seedInstalled(home: Path) {
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
            """.trimIndent(),
        )
        val share = home.resolve(".local").resolve("share").resolve("splice")
        Files.createDirectories(share)
        share.resolve("splice-launch").writeString("#!/usr/bin/env bash\n")
        InstallCommand().install("--all", env = noEnv)
    }

    @Test
    fun `corrupt topology refuses --all loudly instead of exit 0 - DR-101`(@TempDir home: Path) {
        withHome(home) {
            seedInstalled(home)
            home.resolve(".config/splice/splice.toml").writeString("[heads.claudex\nnot toml at all")
            val (ok, _, err) = capture { InstallCommand().uninstall("--all", env = noEnv) }
            assertFalse(ok, "an unreadable topology must fail --all, not exit 0")
            assertTrue(err.contains("unreadable"), "the failure must name the problem; stderr=$err")
            assertTrue(err.contains("splice.toml"), "the failure must name the file; stderr=$err")
            assertTrue(
                home.resolve(".local/bin/claudex").isSymbolicLink(),
                "no half-removal on a refused --all: retry after the fix removes everything",
            )
        }
    }

    @Test
    fun `absent topology removes the self-link and says so - DR-101`(@TempDir home: Path) {
        withHome(home) {
            seedInstalled(home)
            Files.delete(home.resolve(".config/splice/splice.toml"))
            val (ok, out, _) = capture { InstallCommand().uninstall("--all", env = noEnv) }
            assertTrue(ok, "a fresh box with no config is not a failure")
            assertFalse(Files.exists(home.resolve(".local/bin/splice"), NOFOLLOW_LINKS))
            assertTrue(out.contains("no config"), "the partial removal must be SAID; stdout=$out")
        }
    }
}

// DR-169 (grok-splice source sweep, confirmed by reading InstallLinker): a wrapper command is
// head.claude.command or the head key — an unsanitized TOML string — and bin.resolve honoured
// whatever it held. A leading ../ normalized OUT of bin and an absolute value discarded bin
// entirely, so install CREATED and uninstall DELETED symlinks anywhere the user could write.
// requireReplaceableLink only ever asked whether the entry was a symlink, never where it was.
//
// Its own class: InstallCommandTest is the happy-path suite and this is a containment law, which
// is also why every arm asserts on the FILESYSTEM OUTSIDE bin rather than on the verb's return.
class InstallContainmentTest {

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

    /** A topology whose single head carries [command] as its wrapper name. */
    private fun seedWithCommand(home: Path, command: String) {
        val cfg = home.resolve(".config").resolve("splice")
        Files.createDirectories(cfg)
        Files.writeString(
            cfg.resolve("splice.toml"),
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
            command = "$command"
            """.trimIndent(),
        )
        val share = home.resolve(".local").resolve("share").resolve("splice")
        Files.createDirectories(share)
        Files.writeString(share.resolve("splice-launch"), "#!/usr/bin/env bash\n")
    }

    @Test
    fun `a relative escape in a wrapper command is refused, creating nothing - DR-169`(@TempDir home: Path) {
        withHome(home) {
            seedWithCommand(home, "../escaped")
            assertThrows<IllegalStateException> { InstallCommand().install("--all", env = noEnv) }
            // The assertion is the filesystem, not the exception: bin's PARENT is where ../escaped
            // lands, and before DR-169 a symlink appeared there.
            val outside = home.resolve(".local").resolve("escaped")
            assertFalse(Files.exists(outside, NOFOLLOW_LINKS), "nothing may be created outside bin")
        }
    }

    @Test
    fun `an absolute wrapper command is refused, creating nothing - DR-169`(@TempDir home: Path) {
        withHome(home) {
            val target = home.resolve("absolute-escape")
            seedWithCommand(home, target.toString())
            assertThrows<IllegalStateException> { InstallCommand().install("--all", env = noEnv) }
            // bin.resolve(absolute) discards bin altogether, so this one never went near it.
            assertFalse(Files.exists(target, NOFOLLOW_LINKS), "an absolute command must not be claimed")
        }
    }

    @Test
    fun `uninstall refuses an escaping arg and leaves the outside link alone - DR-169`(@TempDir home: Path) {
        withHome(home) {
            // No topology at all, which is the path that hands the operator's string through
            // verbatim (DR-101: the operator named the link, the topology only disambiguates).
            val bin = home.resolve(".local").resolve("bin")
            Files.createDirectories(bin)
            val victimTarget = home.resolve("victim-target")
            Files.writeString(victimTarget, "sentinel")
            val outsideLink = home.resolve(".local").resolve("escaped")
            Files.createSymbolicLink(outsideLink, victimTarget)

            assertFalse(InstallCommand().uninstall("../escaped", env = noEnv), "the verb must report failure")

            assertTrue(Files.exists(outsideLink, NOFOLLOW_LINKS), "a link outside bin must survive uninstall")
        }
    }

    @Test
    fun `an ordinary wrapper command still installs and uninstalls - DR-169 control`(@TempDir home: Path) {
        withHome(home) {
            seedWithCommand(home, "claudex")
            assertTrue(InstallCommand().install("--all", env = noEnv))
            val link = home.resolve(".local").resolve("bin").resolve("claudex")
            assertTrue(link.isSymbolicLink(), "the containment law must not reject a normal name")
            assertTrue(InstallCommand().uninstall("--all", env = noEnv))
            assertFalse(Files.exists(link, NOFOLLOW_LINKS), "and uninstall must still remove it")
        }
    }
}
