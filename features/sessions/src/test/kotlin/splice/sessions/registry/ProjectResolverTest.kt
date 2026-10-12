// NEW: Oct 11, 2026 — the one project answer (Marlin's ruling): with a Projects folder set, mythos/repo,
// mythos/Splice-CPO and every worktree under mythos are the project "mythos"; with none set, a repo is named by its
// remote. The layouts are built with the files git writes, as RepoResolverTest does.
package splice.sessions.registry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProjectResolverTest {

    @TempDir
    lateinit var tmp: Path

    private fun repo(at: Path, remote: String? = null): Path {
        Files.createDirectories(at.resolve(".git"))
        remote?.let { Files.writeString(at.resolve(".git/config"), "[remote \"origin\"]\n\turl = $it\n") }
        return at
    }

    private fun worktree(of: Path, at: Path, name: String): Path {
        Files.createDirectories(of.resolve(".git/worktrees/$name"))
        Files.writeString(of.resolve(".git/worktrees/$name/commondir"), "../..\n")
        Files.createDirectories(at)
        Files.writeString(at.resolve(".git"), "gitdir: ${of.resolve(".git/worktrees/$name")}\n")
        return at
    }

    private fun resolver(base: Path, folders: List<String>) =
        ProjectResolver(folders, RepoResolver(extraRoots = listOf(base.toString()), home = null))

    @Test
    fun `with a Projects folder set the repo, a sibling folder and worktrees all resolve to the folder under it`() {
        val base = tmp.toRealPath()
        val projects = Files.createDirectories(base.resolve("projects"))
        val main = repo(projects.resolve("mythos/repo"), "git@github.com:torad/splice.git")
        Files.createDirectories(projects.resolve("mythos/Splice-CPO"))
        val inside = worktree(main, main.resolve(".claude/worktrees/feature"), "feature")
        val outside = worktree(main, base.resolve("elsewhere/wt"), "wt")
        val resolver = resolver(base, listOf(projects.toString()))

        val expected = ProjectRef(
            projects.resolve("mythos").toString(),
            "mythos",
            "mythos",
            ProjectSource.FOLDER,
            projects.toString(),
        )
        listOf(
            main,
            projects.resolve("mythos/Splice-CPO"),
            inside,
            outside,
            Files.createDirectories(main.resolve("features/deep")),
            projects.resolve("mythos"),
        ).forEach { assertEquals(expected, resolver.of(it.toString()), it.toString()) }
    }

    @Test
    fun `a session outside every Projects folder has no project`() {
        val base = tmp.toRealPath()
        val projects = Files.createDirectories(base.resolve("projects"))
        val stray = repo(base.resolve("stray"))
        val resolver = resolver(base, listOf(projects.toString()))

        assertNull(resolver.of(stray.toString()))
        assertNull(resolver.of(projects.toString()))
    }

    @Test
    fun `two Projects folders each holding the same name label both with their parent folder`() {
        val base = tmp.toRealPath()
        val work = Files.createDirectories(base.resolve("work/repo"))
        val home = Files.createDirectories(base.resolve("mythos/repo"))
        Files.createDirectories(base.resolve("mythos/solo"))
        val resolver = resolver(base, listOf(base.resolve("work").toString(), base.resolve("mythos").toString()))

        assertEquals("work/repo", resolver.of(work.toString())?.label)
        assertEquals("mythos/repo", resolver.of(home.toString())?.label)
        assertEquals("repo", resolver.of(work.toString())?.name)
        assertEquals("solo", resolver.of(base.resolve("mythos/solo").toString())?.label)
    }

    @Test
    fun `with no Projects folder a repo is named by its remote and a worktree folds into it`() {
        val base = tmp.toRealPath()
        val plain = repo(base.resolve("checkout-dir"), "https://user:secret@github.com/torad/splice.git")
        val noRemote = repo(base.resolve("local-only"))
        val linked = worktree(plain, base.resolve("elsewhere/wt"), "wt")
        val resolver = resolver(base, emptyList())

        assertEquals(
            ProjectRef(plain.toString(), "splice", "splice", ProjectSource.REPO),
            resolver.of(plain.toString()),
        )
        assertEquals(resolver.of(plain.toString()), resolver.of(linked.toString()))
        assertEquals("local-only", resolver.of(noRemote.toString())?.name)
        assertNull(resolver.of(base.resolve("no-repo-here").toString()))
    }

    @Test
    fun `two repos with the same name but different owners are labelled with their owner`() {
        val base = tmp.toRealPath()
        val one = repo(base.resolve("a"), "git@github.com:torad-labs/splice.git")
        val two = repo(base.resolve("b"), "https://github.com/someone/splice.git")
        val resolver = resolver(base, emptyList())

        resolver.of(one.toString())
        resolver.of(two.toString())

        assertEquals("torad-labs/splice", resolver.of(one.toString())?.label)
        assertEquals("someone/splice", resolver.of(two.toString())?.label)
        assertEquals("splice", resolver.of(one.toString())?.name)
    }

    @Test
    fun `a saved Projects folder that no longer exists is skipped and its sessions fall back to the repo rule`() {
        val base = tmp.toRealPath()
        val main = repo(base.resolve("deleted-work/proj"), "git@github.com:torad/proj.git")
        val resolver = resolver(base, listOf(base.resolve("deleted-work").toString()))
        assertEquals(ProjectSource.FOLDER, resolver.of(main.toString())?.source)

        main.toFile().deleteRecursively()
        base.resolve("deleted-work").toFile().deleteRecursively()
        val again = repo(base.resolve("elsewhere/proj"), "git@github.com:torad/proj.git")
        assertEquals(
            ProjectRef(again.toString(), "proj", "proj", ProjectSource.REPO),
            resolver.of(again.toString()),
        )
    }
}
