package splice.sessions.registry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class RepoOriginTest {
    @TempDir
    lateinit var root: Path

    private fun config(text: String): Path {
        val git = Files.createDirectories(root.resolve(".git"))
        return Files.writeString(git.resolve("config"), text)
    }

    @Test
    fun `https userinfo is stripped but path and scp git identity survive`() {
        val values = mapOf(
            "https://user:secret@github.com/torad-labs/splice.git" to "https://github.com/torad-labs/splice.git",
            "https://token@github.com/torad-labs/splice.git" to "https://github.com/torad-labs/splice.git",
            "https://user:secret@@github.com/torad-labs/splice.git" to "https://github.com/torad-labs/splice.git",
            "git@github.com:torad-labs/splice.git" to "git@github.com:torad-labs/splice.git",
            "ssh://git@github.com/torad-labs/splice.git" to "ssh://github.com/torad-labs/splice.git",
        )
        values.entries.forEachIndexed { i, (raw, expected) ->
            val file = config("[remote \"origin\"]\nurl = $raw\n")
            Files.setLastModifiedTime(file, FileTime.fromMillis(10_000L + i * 1_000L))
            assertEquals(expected, RepoOrigin.of(root.toString()))
        }
    }

    @Test
    fun `missing origin and non origin remotes are absent`() {
        assertNull(RepoOrigin.of(root.toString()))
        config("[remote \"upstream\"]\nurl = https://github.com/other/repo.git\n")
        assertNull(RepoOrigin.of(root.toString()))
    }

    @Test
    fun `quoted url and comments are parsed without leaking credentials`() {
        config("[remote \"origin\"] # comment\n url = \"https://token@github.com/torad-labs/splice.git\" ; comment\n")
        assertEquals("https://github.com/torad-labs/splice.git", RepoOrigin.of(root.toString()))
    }

    @Test
    fun `warm roots use cached config until its stamp changes`() {
        val file = config("[remote \"origin\"]\nurl = https://host/first.git\n")
        Files.setLastModifiedTime(file, FileTime.fromMillis(10_000))
        assertEquals("https://host/first.git", RepoOrigin.of(root.toString()))
        config("[remote \"origin\"]\nurl = https://host/other.git\n")
        Files.setLastModifiedTime(file, FileTime.fromMillis(10_000))
        assertEquals("https://host/first.git", RepoOrigin.of(root.toString()))
        Files.setLastModifiedTime(file, FileTime.fromMillis(20_000))
        assertEquals("https://host/other.git", RepoOrigin.of(root.toString()))
    }

    @Test
    fun `linked worktrees read the shared git config without a subprocess`() {
        val common = Files.createDirectories(root.resolve("shared"))
        val work = Files.createDirectories(common.resolve("worktrees/w"))
        Files.writeString(root.resolve(".git"), "gitdir: shared/worktrees/w\n")
        Files.writeString(work.resolve("commondir"), "../..\n")
        Files.writeString(common.resolve("config"), "[remote \"origin\"]\nurl = git@github.com:torad-labs/splice.git\n")
        assertEquals("git@github.com:torad-labs/splice.git", RepoOrigin.of(root.toString()))
    }
}
