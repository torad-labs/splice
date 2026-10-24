// NEW: V4-445 — plain `claude` stays wrapped across Claude Code's own updates. The updater re-points
// ~/.local/bin/claude at the new version on every release (2.1.282 to .285 in four days) and deletes the older
// ones, which used to replace the shim and pin the recorded binary to a file that was gone. WrapRig.update() does
// what the updater does, on a real filesystem, and WrappedHead.reconcile() is what the daemon runs at start and
// when the bin directory changes. Nothing here may touch the operator's ~/.claude.json or ~/.claude.
package splice.client.wrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.writeText

class WrapSurvivesUpdateTest {

    private fun wrapped(home: Path): WrapRig = WrapRig(home).also {
        it.linkCmdToReal()
        assertTrue(it.head.wrap() is WrapResult.Ok)
    }

    @Test
    fun `an update that re-points claude is put right, on the new version`(@TempDir home: Path) {
        val rig = wrapped(home)
        val vanilla = VanillaState(home)
        val next = rig.update("2.1.286")
        assertEquals("separate", rig.head.status().mode, "the updater replaced the shim: this is the gap")

        val result = rig.head.reconcile()

        assertEquals(ReconcileResult.Rewrapped(next.toRealPath().toString()), result)
        assertEquals("wrapped", rig.head.status().mode)
        assertEquals(rig.shim.toRealPath(), rig.cmd.toRealPath(), "claude is the shim again")
        assertEquals(next.toRealPath().toString(), rig.head.realBinaryPath(), "and a launch runs the new version")
        assertTrue(rig.head.launchThrough("claude") != null)
        vanilla.assertUntouched()
    }

    @Test
    fun `a daemon that was down during the update reconciles at its next start`(@TempDir home: Path) {
        val rig = wrapped(home)
        val next = rig.update("2.1.287")
        // The daemon restarts: a new WrappedHead over the same files, holding nothing from before.
        val restarted = WrappedHead(home, rig.installPaths, rig.stateStore)

        assertEquals(ReconcileResult.Rewrapped(next.toRealPath().toString()), restarted.reconcile())
        assertEquals(rig.shim.toRealPath(), rig.cmd.toRealPath())
    }

    @Test
    fun `a recorded binary the updater deleted reconciles to the live one, without failing a launch`(
        @TempDir home: Path,
    ) {
        val rig = wrapped(home)
        // The updater installed the new version and cleaned up the old, but claude is still the shim here.
        val next = rig.versions.resolve("2.1.288").also {
            it.writeText("#!/bin/sh\n")
            it.toFile().setExecutable(true)
        }
        Files.delete(rig.realBinary)
        assertEquals("wrapped", rig.head.status().mode)

        assertEquals(next.toString(), rig.head.realBinaryPath(), "a launch heals the state before it execs a dead path")
        assertEquals(ReconcileResult.Intact, rig.head.reconcile())
    }

    @Test
    fun `a newer version installed beside a recorded one that still exists is what the next launch runs`(
        @TempDir home: Path,
    ) {
        // 2026-09-30: the updater downloaded 2.1.286 beside 2.1.285, kept 2.1.285 and left claude on the shim, so
        // every launch ran 2.1.285 for five hours.
        val rig = wrapped(home)
        val next = rig.versions.resolve("2.1.286").also {
            it.writeText("#!/bin/sh\n")
            it.toFile().setExecutable(true)
        }
        assertTrue(Files.isExecutable(rig.realBinary), "the recorded version is still there")

        assertEquals(next.toString(), rig.head.realBinaryPath(), "a launch runs the newest installed version")
        assertEquals(next.toString(), rig.stateStore.read().state?.realBinaryPath, "and the record says so")
        assertEquals(ReconcileResult.Intact, rig.head.reconcile())
    }

    @Test
    fun `the periodic reconcile moves an intact wrap onto a newer version`(@TempDir home: Path) {
        val rig = wrapped(home)
        val next = rig.versions.resolve("2.1.287").also {
            it.writeText("#!/bin/sh\n")
            it.toFile().setExecutable(true)
        }

        assertEquals(ReconcileResult.Rewrapped(next.toString()), rig.head.reconcile())
        assertEquals(rig.shim.toRealPath(), rig.cmd.toRealPath(), "claude stays the shim")
    }

    @Test
    fun `only a version-named file counts as a version, and never an older one`(@TempDir home: Path) {
        val rig = wrapped(home)
        listOf("2.1.300.tmp", "claude-helper", "2.1.1").forEach { name ->
            rig.versions.resolve(name).also {
                it.writeText("#!/bin/sh\n")
                it.toFile().setExecutable(true)
            }
        }

        assertEquals(rig.realBinary.toString(), rig.head.realBinaryPath())
        assertEquals(ReconcileResult.Intact, rig.head.reconcile())
    }

    @Test
    fun `a claude installed outside a versions directory is never swapped for another executable beside it`(
        @TempDir home: Path,
    ) {
        val rig = WrapRig(home)
        val own = home.resolve("opt").also { Files.createDirectories(it) }
        val real = own.resolve("claude").also {
            it.writeText("#!/bin/sh\n")
            it.toFile().setExecutable(true)
        }
        own.resolve("9.9.9").also {
            it.writeText("#!/bin/sh\n")
            it.toFile().setExecutable(true)
        }
        Files.createSymbolicLink(rig.cmd, real)
        assertTrue(rig.head.wrap() is WrapResult.Ok)

        assertEquals(real.toRealPath().toString(), rig.head.realBinaryPath())
        assertEquals(ReconcileResult.Intact, rig.head.reconcile())
    }

    @Test
    fun `the newest version is the highest number, not the last name alphabetically`(@TempDir home: Path) {
        val rig = wrapped(home)
        val newest = listOf("2.1.99", "2.1.285", "2.1.9").map { version ->
            rig.versions.resolve(version).also {
                it.writeText("#!/bin/sh\n")
                it.toFile().setExecutable(true)
            }
        }[1]
        Files.delete(rig.realBinary)

        assertEquals(ReconcileResult.Rewrapped(newest.toString()), rig.head.reconcile())
    }

    @Test
    fun `unwrap after an update puts claude on the new version, not the one that was wrapped`(@TempDir home: Path) {
        val rig = wrapped(home)
        val vanilla = VanillaState(home)
        val next = rig.update("2.1.289")
        rig.head.reconcile()

        val result = rig.head.unwrap()

        assertTrue(result is UnwrapResult.Ok, "$result")
        assertEquals(next.toRealPath(), rig.cmd.toRealPath())
        assertTrue(rig.stateStore.read() is StoredWrap.Absent, "the state is cleared")
        vanilla.assertUntouched()
    }

    @Test
    fun `unwrap before reconciliation selects a newer version while the recorded binary remains`(@TempDir home: Path) {
        val rig = wrapped(home)
        val vanilla = VanillaState(home)
        val next = rig.versions.resolve("2.1.286").also {
            it.writeText("#!/bin/sh\n")
            it.toFile().setExecutable(true)
        }
        assertTrue(Files.isExecutable(rig.realBinary))

        assertTrue(rig.head.unwrap() is UnwrapResult.Ok)
        assertEquals(next, rig.cmd.toRealPath())
        assertTrue(rig.stateStore.read() is StoredWrap.Absent, "the state is cleared")
        vanilla.assertUntouched()
    }

    @Test
    fun `unwrap when the wrapped version was deleted points claude at the newest beside it`(@TempDir home: Path) {
        val rig = wrapped(home)
        val next = rig.versions.resolve("2.1.290").also {
            it.writeText("#!/bin/sh\n")
            it.toFile().setExecutable(true)
        }
        Files.delete(rig.realBinary)

        assertTrue(rig.head.unwrap() is UnwrapResult.Ok)
        assertEquals(next, rig.cmd.readSymbolicLink())
    }

    @Test
    fun `the directory event unwrap itself causes is not read as an update`(@TempDir home: Path) {
        val rig = wrapped(home)
        assertTrue(rig.head.unwrap() is UnwrapResult.Ok)
        // The daemon's watch fires on unwrap's own swap of the symlink.
        assertEquals(ReconcileResult.NotWrapped, rig.head.reconcile())
        assertTrue(rig.cmd.isSymbolicLink())
        assertEquals(rig.realBinary, rig.cmd.readSymbolicLink(), "claude stayed on the real binary")
    }

    @Test
    fun `an intact wrap and an unwrapped home both reconcile to nothing`(@TempDir home: Path) {
        val rig = WrapRig(home)
        rig.linkCmdToReal()
        assertEquals(ReconcileResult.NotWrapped, rig.head.reconcile())
        assertTrue(rig.head.wrap() is WrapResult.Ok)
        val before = Files.readString(home.resolve("state/claude-head-wrap.json"))
        assertEquals(ReconcileResult.Intact, rig.head.reconcile())
        assertEquals(before, Files.readString(home.resolve("state/claude-head-wrap.json")), "state not rewritten")
    }

    @Test
    fun `an update in the middle of its swap is waited for, never guessed at`(@TempDir home: Path) {
        val rig = wrapped(home)
        Files.delete(rig.cmd)
        assertTrue(rig.head.reconcile() is ReconcileResult.Waiting, "claude is missing for an instant")
        assertFalse(rig.cmd.toFile().exists() || rig.cmd.isSymbolicLink(), "nothing was put there")

        Files.createSymbolicLink(rig.cmd, home.resolve("versions/not-there-yet"))
        assertTrue(rig.head.reconcile() is ReconcileResult.Waiting, "a link to a version not yet written")
        assertEquals(home.resolve("versions/not-there-yet"), rig.cmd.readSymbolicLink(), "left exactly as found")

        Files.delete(rig.cmd)
        rig.cmd.writeText("#!/bin/sh\necho a regular file\n")
        assertTrue(rig.head.reconcile() is ReconcileResult.Waiting, "a regular file is not ours to replace")
        assertFalse(rig.cmd.isSymbolicLink())
    }

    @Test
    fun `no shim to put back is waited for, so claude is never pointed at nothing`(@TempDir home: Path) {
        val rig = wrapped(home)
        val next = rig.update("2.1.291")
        Files.delete(rig.shim)
        assertTrue(rig.head.reconcile() is ReconcileResult.Waiting)
        assertEquals(next, rig.cmd.readSymbolicLink())
    }
}
