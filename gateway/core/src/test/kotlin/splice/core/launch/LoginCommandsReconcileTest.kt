// DR-39 redo: once blank-login heads join, the commands dir reconciles on EVERY launch (the
// materializer exempts commands' real-dir decline on LoginInterception's promise to do so), so the
// reconciliation must be SAFE at steady state: a correct link is untouched (no inode churn, no
// delete-then-create window), a stale entry is replaced through a staged sibling in one atomic
// rename, a whole-dir symlink is converted by staging the COMPLETE real dir before the unlink, and
// any staged-name collision fails BEFORE the destination is touched.
package splice.core.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class LoginCommandsReconcileTest {

    private fun wire(
        configDir: Path,
        loginCommand: String,
        globalCommands: Path?,
        log: MutableList<String> = mutableListOf(),
    ) = LoginInterception.wire(
        configDir = configDir,
        loginCommand = loginCommand,
        signInLabel = "OpenRouter",
        globalCommands = globalCommands,
        viaBrowser = false,
        tokenCapture = null,
        log = LogSink { log += it },
    )

    private fun globalWith(tmp: Path, vararg names: String): Path {
        val global = Files.createDirectories(tmp.resolve("global-commands"))
        names.forEach { Files.writeString(global.resolve(it), "# $it") }
        return global
    }

    private fun fileKey(path: Path): Any? =
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()

    // codex's steady-state repro: most launches find the link already correct, and the old
    // unconditional delete-and-recreate churned its inode every launch — with a crash window
    // between the two syscalls where the command was simply GONE.
    @Test
    fun `an already-correct command link survives reconciliation with its inode - DR-39`(@TempDir tmp: Path) {
        val global = globalWith(tmp, "global.md")
        val commands = Files.createDirectories(tmp.resolve("commands"))
        val link = commands.resolve("global.md")
        Files.createSymbolicLink(link, global.resolve("global.md"))
        val before = fileKey(link)

        wire(tmp, "openrouter login", global)

        assertTrue(Files.isSymbolicLink(link))
        assertEquals(global.resolve("global.md"), Files.readSymbolicLink(link))
        assertEquals(before, fileKey(link), "steady state must not churn the link inode")
    }

    @Test
    fun `a stale regular file and a wrong-target link both become the shared entry`(@TempDir tmp: Path) {
        val global = globalWith(tmp, "stale.md", "wrong.md")
        val commands = Files.createDirectories(tmp.resolve("commands"))
        Files.writeString(commands.resolve("stale.md"), "stale local copy")
        Files.createSymbolicLink(commands.resolve("wrong.md"), tmp.resolve("decoy.md"))

        wire(tmp, "openrouter login", global)

        assertEquals(global.resolve("stale.md"), Files.readSymbolicLink(commands.resolve("stale.md")))
        assertEquals(global.resolve("wrong.md"), Files.readSymbolicLink(commands.resolve("wrong.md")))
        val debris = Files.newDirectoryStream(commands, ".*.staged-*").use { it.count() }
        assertEquals(0, debris, "a successful replacement leaves no staged debris")
    }

    // The preservation half of the staged idiom: an injected collision (a non-empty directory at
    // the staged name) fails the replacement BEFORE dst is touched. Under delete-then-create the
    // stale entry would already be gone.
    @Test
    fun `a staged-name collision leaves the stale entry in place, never missing - DR-39`(@TempDir tmp: Path) {
        val global = globalWith(tmp, "global.md")
        val commands = Files.createDirectories(tmp.resolve("commands"))
        val stale = commands.resolve("global.md")
        Files.writeString(stale, "stale local copy")
        val staged = commands.resolve(".global.md.staged-${ProcessHandle.current().pid()}")
        Files.createDirectories(staged.resolve("junk"))
        val log = mutableListOf<String>()

        wire(tmp, "openrouter login", global, log)

        assertFalse(Files.isSymbolicLink(stale), "the failed replacement must not have swapped dst")
        assertEquals("stale local copy", Files.readString(stale), "a failed replacement preserves dst")
        assertTrue(log.any { it.contains("NOT installed") }, "the failed leg must log: $log")
    }

    @Test
    fun `a whole-dir commands symlink becomes a complete real dir with no staged debris`(@TempDir tmp: Path) {
        val global = globalWith(tmp, "global.md")
        val elsewhere = Files.createDirectories(tmp.resolve("elsewhere"))
        Files.writeString(elsewhere.resolve("old.md"), "operator content")
        val commands = tmp.resolve("commands")
        Files.createSymbolicLink(commands, elsewhere)

        wire(tmp, "openrouter login", global)

        assertFalse(Files.isSymbolicLink(commands), "the dir symlink must convert to a real dir")
        assertTrue(Files.isRegularFile(commands.resolve("login.md")))
        assertEquals(global.resolve("global.md"), Files.readSymbolicLink(commands.resolve("global.md")))
        assertEquals("operator content", Files.readString(elsewhere.resolve("old.md")), "the old target survives")
        val debris = Files.newDirectoryStream(tmp, ".commands.staged-*").use { it.count() }
        assertEquals(0, debris, "a successful conversion leaves no staged debris")
    }

    // codex's ENOSPC-after-delete class, made deterministic: the conversion must stage the real dir
    // COMPLETE before unlinking. A collision at the stage (a regular file) fails first — under the
    // old delete-then-createDirectories the working symlink was already gone.
    @Test
    fun `a commands-dir stage collision leaves the working symlink untouched - DR-39`(@TempDir tmp: Path) {
        val global = globalWith(tmp, "global.md")
        val elsewhere = Files.createDirectories(tmp.resolve("elsewhere"))
        val commands = tmp.resolve("commands")
        Files.createSymbolicLink(commands, elsewhere)
        Files.writeString(tmp.resolve(".commands.staged-${ProcessHandle.current().pid()}"), "collide")
        val log = mutableListOf<String>()

        wire(tmp, "openrouter login", global, log)

        assertTrue(Files.isSymbolicLink(commands), "a failed conversion must leave the working link")
        assertEquals(elsewhere, Files.readSymbolicLink(commands))
        assertTrue(log.any { it.contains("NOT installed") }, "the failed leg must log: $log")
    }

    // DR-39 redo (codex repro): a client-auth head — blank loginCommand, no capture — still shares
    // the operator's commands, and the materializer exempts commands' real-dir decline on the
    // promise that wire() reconciles them. wire() used to return before touching anything: local
    // commands dir + global entries + blank login => global entries absent, log empty.
    @Test
    fun `a blank-login head still receives the operator's commands entries - DR-39`(@TempDir tmp: Path) {
        val global = globalWith(tmp, "global.md")
        val commands = Files.createDirectories(tmp.resolve("commands"))
        Files.writeString(commands.resolve("own.md"), "the head's own command")

        val hooks = wire(tmp, "", global)

        assertTrue(hooks.isEmpty(), "a blank-login head gets no login hooks")
        assertEquals(global.resolve("global.md"), Files.readSymbolicLink(commands.resolve("global.md")))
        assertEquals("the head's own command", Files.readString(commands.resolve("own.md")))
        assertFalse(Files.exists(commands.resolve("login.md")), "no /login command on a client-auth head")
    }
}
