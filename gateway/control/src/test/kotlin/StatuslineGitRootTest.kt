// NEW (finding 5, review 2026-07-23): the unauthenticated /statusline endpoint only runs `git -C`
// under $HOME, /tmp, or an operator-trusted root. That containment must resolve symlinks FIRST — a
// lexical startsWith() check let a symlink sitting UNDER a trusted root (e.g. /tmp/link) point git at
// a target OUTSIDE the roots. safeGitCwd now resolves real paths on both sides, so the escape is
// rejected while a genuinely-contained symlink still resolves (and git runs in the resolved path).
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.control.StatuslineRenderer
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class StatuslineGitRootTest {

    private val renderer = StatuslineRenderer(label = "codex")

    private fun git(repo: java.nio.file.Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", repo.toString()) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }

    @Test
    fun `a symlink under a trusted root pointing outside the roots is rejected`(@TempDir tmpDir: Path) {
        // /usr exists on every Linux host and is NOT under $HOME or /tmp — the review's concrete
        // "/tmp/link -> /outside/roots" escape. The link itself lexically sits under /tmp (trusted),
        // so only realpath resolution reveals the escape: the old startsWith check passed this.
        val outside = Paths.get("/usr")
        assumeTrue(Files.isDirectory(outside), "/usr must exist to serve as an outside-roots target")
        val link = tmpDir.resolve("repo-link")
        Files.createSymbolicLink(link, outside)
        assertNull(renderer.safeGitCwd(link.toString()), "a symlink escaping the trusted roots must not run git")
    }

    @Test
    fun `a symlink whose real path stays under a trusted root resolves to that real path`(@TempDir tmpDir: Path) {
        val realRepo = Files.createDirectory(tmpDir.resolve("real-repo"))
        val link = tmpDir.resolve("repo-link")
        Files.createSymbolicLink(link, realRepo)
        // Trust the temp tree explicitly (not relying on java.io.tmpdir == /tmp). Contained: the
        // resolved real path stays under the trusted root, so it is returned — and it is the RESOLVED
        // path (proving toRealPath ran), which is what git -C is handed.
        val trusting = StatuslineRenderer(label = "codex", extraGitRoots = listOf(tmpDir.toString()))
        assertEquals(realRepo.toRealPath(), trusting.safeGitCwd(link.toString()))
    }

    @Test
    fun `the branch cache reuses within the window and re-reads after expiry`(@TempDir tmpDir: Path) {
        val repo = Files.createDirectory(tmpDir.resolve("repo"))
        git(repo, "init", "-b", "first")
        // Injected clock (DR-22c): the old form raced two real git round-trips against the real 2s
        // TTL, flaking on loaded runners — and could not test expiry at all without sleeping.
        var clock = 1_000_000L
        val trusting = StatuslineRenderer(
            label = "codex",
            extraGitRoots = listOf(tmpDir.toString()),
            now = WallClock { clock },
        )
        val stdin = """{"cwd":"$repo"}"""

        val first = trusting.render(stdin, usage = null, warnPct = 0, warnTokens5h = 0)
        git(repo, "symbolic-ref", "HEAD", "refs/heads/second")
        val second = trusting.render(stdin, usage = null, warnPct = 0, warnTokens5h = 0)
        clock += 60_000L
        val third = trusting.render(stdin, usage = null, warnPct = 0, warnTokens5h = 0)

        assertEquals(true, first.contains("⎇ first"), first)
        assertEquals(true, second.contains("⎇ first"), "the second tick must reuse the cached branch: $second")
        assertEquals(true, third.contains("⎇ second"), "an expired entry must re-read the real branch: $third")
    }

    @Test
    fun `a non-existent path is rejected because toRealPath requires existence`() {
        assertNull(renderer.safeGitCwd("/tmp/splice-statusline-does-not-exist-xyzzy"))
    }
}
