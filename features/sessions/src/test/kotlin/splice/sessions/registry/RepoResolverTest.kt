// NEW: V4-130 — RepoResolver places a session's cwd in its git repository from the filesystem alone.
// The layouts are built with the exact files git writes (read from this checkout, 2026-09-18): a main
// checkout's `.git` directory, a linked worktree's `.git` file `gitdir: <abs>/.git/worktrees/<name>`
// whose gitdir holds `commondir` = `../..`, and a gitdir with no commondir (a submodule). Every reason
// a cwd is not placed has its own case, because the console prints the reason.
package splice.sessions.registry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.ElapsedClock
import java.nio.file.Files
import java.nio.file.Path

class RepoResolverTest {

    @TempDir
    lateinit var tmp: Path

    private fun layout(): Triple<Path, Path, Path> {
        val base = tmp.toRealPath()
        val main = Files.createDirectories(base.resolve("repo"))
        Files.createDirectories(main.resolve(".git/worktrees/feature"))
        Files.writeString(main.resolve(".git/worktrees/feature/commondir"), "../..\n")
        val worktree = Files.createDirectories(main.resolve(".claude/worktrees/feature"))
        Files.writeString(worktree.resolve(".git"), "gitdir: ${main.resolve(".git/worktrees/feature")}\n")
        val submodule = Files.createDirectories(main.resolve("vendor/lib"))
        Files.createDirectories(main.resolve(".git/modules/lib"))
        Files.writeString(submodule.resolve(".git"), "gitdir: ../../.git/modules/lib\n")
        return Triple(main, worktree, submodule)
    }

    @Test
    fun `a main checkout, a linked worktree and a submodule each land in the right repo`() {
        val (main, worktree, submodule) = layout()
        val resolver = RepoResolver(home = tmp.toString())
        Files.createDirectories(main.resolve("src/deep"))
        Files.createDirectories(worktree.resolve("gateway"))
        assertEquals(RepoRoot(main.toString()), resolver.resolve(main.resolve("src/deep").toString()))
        assertEquals(
            RepoRoot(main.toString(), worktree = worktree.toString()),
            resolver.resolve(worktree.resolve("gateway").toString()),
            "a linked worktree folds into its shared repo, and keeps its own path as the sub-label",
        )
        assertEquals(RepoRoot(submodule.toString()), resolver.resolve(submodule.toString()))
    }

    @Test
    fun `every cwd the resolver will not place answers with itself and its reason`() {
        val resolver = RepoResolver(home = null)
        assertEquals(RepoRoot("relative/dir", reason = REASON_NOT_ABSOLUTE), resolver.resolve("relative/dir"))
        val missing = tmp.resolve("gone").toString()
        assertEquals(RepoRoot(missing, reason = REASON_MISSING), resolver.resolve(missing))
        assertEquals(RepoRoot("/usr", reason = REASON_UNTRUSTED), resolver.resolve("/usr"))
        val plain = Files.createDirectories(tmp.resolve("plain")).toString()
        assertEquals(RepoRoot(plain, reason = REASON_NO_REPO), resolver.resolve(plain))
    }

    @Test
    fun `the walk never reads a git dir above the trusted root that holds the cwd`() {
        val base = tmp.toRealPath()
        Files.createDirectories(base.resolve("outer/.git"))
        val inner = Files.createDirectories(base.resolve("outer/inner/work"))
        // /tmp is trusted too, but the LONGEST trusted root containing the cwd is the boundary.
        val resolver = RepoResolver(extraRoots = listOf(base.resolve("outer/inner").toString()), home = null)
        assertEquals(RepoRoot(inner.toString(), reason = REASON_NO_REPO), resolver.resolve(inner.toString()))
    }

    @Test
    fun `an answer is cached for its ttl and re-read after it`() {
        var now = 0L
        val resolver = RepoResolver(home = tmp.toString(), clock = ElapsedClock { now })
        val dir = Files.createDirectories(tmp.toRealPath().resolve("later")).toString()
        assertEquals(REASON_NO_REPO, resolver.resolve(dir).reason)
        Files.createDirectories(tmp.toRealPath().resolve("later/.git"))
        now = 1_999L
        assertEquals(REASON_NO_REPO, resolver.resolve(dir).reason, "inside the ttl the cached answer stands")
        now = 2_000L
        assertEquals(RepoRoot(dir), resolver.resolve(dir), "past the ttl the layout is read again")
    }
}
