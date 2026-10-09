// NEW: V4-129 — WrappedHead's wrap/unwrap orchestration: the shim swap and the shadowed-symlink
// preservation. V4-445: wrap and unwrap never touch the vanilla ~/.claude.json and ~/.claude, which the
// tests below pin byte for byte. Every fixture is a
// real filesystem under @TempDir (no fakes for Files.* — the safety property under test IS the
// filesystem sequencing), with InstallPaths and WrapStateStore pointed at temp subdirectories so no
// test touches the real ~/.local/bin or ~/.claude-codey/state.
package splice.client.wrap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.InstallPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeText

class WrappedHeadTest {

    @Test
    fun `unwrapped status reports separate and the real path claude resolves to`(@TempDir home: Path) {
        val rig = WrapRig(home)
        rig.linkCmdToReal()
        val status = rig.head.status()
        assertEquals("separate", status.mode)
        assertEquals(rig.realBinary.toRealPath().toString(), status.resolvesTo)
        assertEquals(rig.shim.toString(), status.shimPath)
    }

    @Test
    fun `status with no claude on PATH at all reports separate with a null resolution`(@TempDir home: Path) {
        val rig = WrapRig(home)
        val status = rig.head.status()
        assertEquals("separate", status.mode)
        assertEquals(null, status.resolvesTo)
    }

    @Test
    fun `wrap preserves the shadowed symlink target, plants the real binary in state, and swaps the shim in`(
        @TempDir home: Path,
    ) {
        val rig = WrapRig(home)
        rig.linkCmdToReal()
        val vanilla = VanillaState(home)

        val result = rig.head.wrap()
        assertTrue(result is WrapResult.Ok, "$result")
        result as WrapResult.Ok
        assertEquals("wrapped", result.status.mode)
        assertEquals(rig.realBinary.toString(), result.status.realBinaryPath)

        // The command now resolves to the shim, not the real binary.
        assertTrue(rig.cmd.isSymbolicLink())
        assertEquals(rig.shim.toRealPath(), rig.cmd.toRealPath())

        // The shadowed target survived byte-for-byte in state, for unwrap to restore exactly.
        val state = rig.stateStore.read().state!!
        assertEquals(rig.realBinary.toString(), state.shadowedSymlinkTarget)
        assertEquals(rig.realBinary.toString(), state.realBinaryPath)
        assertEquals(
            """{"home":"$home","state_dir":"${home.resolve("state")}"}""",
            rig.share.resolve("splice-launch-owner.json").readText().trim(),
        )
        assertEquals(
            setOf(OWNER_READ, OWNER_WRITE),
            Files.getPosixFilePermissions(rig.share.resolve("splice-launch-owner.json")),
        )

        // V4-445: the operator's own state is where it was, byte for byte, and wrap added nothing to it.
        vanilla.assertUntouched()
        assertEquals("", state.settingsBackupPath, "there is nothing to back up when nothing is written")
    }

    @Test
    fun `reconcile backfills an older wrap owner without changing its binary or command`(@TempDir home: Path) {
        val rig = WrapRig(home)
        rig.linkCmdToReal()
        assertTrue(rig.head.wrap() is WrapResult.Ok)
        val owner = rig.share.resolve("splice-launch-owner.json")
        Files.delete(owner)
        val state = rig.stateStore.read()
        assertEquals(ReconcileResult.Intact, rig.head.reconcile())
        assertEquals(state, rig.stateStore.read())
        assertEquals(rig.shim.toRealPath(), rig.cmd.toRealPath())
        assertEquals(
            """{"home":"$home","state_dir":"${home.resolve("state")}"}""",
            owner.readText().trim(),
        )
        Files.setPosixFilePermissions(owner, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"))
        assertEquals(ReconcileResult.Intact, rig.head.reconcile())
        assertEquals(setOf(OWNER_READ, OWNER_WRITE), Files.getPosixFilePermissions(owner))
    }

    @Test
    fun `wrap publishes all owner selectors before swapping the command`(@TempDir home: Path) {
        val rig = WrapRig(home)
        rig.linkCmdToReal()
        val profile = mapOf(
            "SPLICE_CONFIG" to home.resolve("custom.toml").toString(),
            "XDG_CONFIG_HOME" to home.resolve("xdg").toString(),
            "SPLICE_CONTROL_PORT" to "4500",
            "CONTROL_PROXY_PORT" to "4501",
        )
        val paths = InstallPaths(binOverride = rig.bin, shareOverride = rig.share, envReader = profile::get)
        val wrap = WrappedHead(
            home = home,
            installPaths = paths,
            stateStore = rig.stateStore,
            symlink = { link, target ->
                val owner = Json.parseToJsonElement(rig.share.resolve("splice-launch-owner.json").readText()).jsonObject
                assertEquals(JsonObject(profile.mapValues { JsonPrimitive(it.value) }), owner["selectors"])
                Files.createSymbolicLink(link, target)
            },
        )
        assertTrue(wrap.wrap() is WrapResult.Ok)
    }

    @Test
    fun `wrap refuses when the shim is not installed`(@TempDir home: Path) {
        val rig = WrapRig(home)
        rig.linkCmdToReal()
        Files.delete(rig.shim)
        val result = rig.head.wrap()
        assertTrue(result is WrapResult.Refused, "$result")
    }

    @Test
    fun `wrap refuses when claude is already wrapped`(@TempDir home: Path) {
        val rig = WrapRig(home)
        rig.linkCmdToReal()
        assertTrue(rig.head.wrap() is WrapResult.Ok)
        val second = rig.head.wrap()
        assertTrue(second is WrapResult.Refused, "$second")
        assertTrue((second as WrapResult.Refused).reason.contains("already wrapped"), second.reason)
    }

    @Test
    fun `wrap refuses a real file at the command path rather than overwriting it`(@TempDir home: Path) {
        val rig = WrapRig(home)
        rig.cmd.writeText("#!/bin/sh\necho not a symlink\n")
        val result = rig.head.wrap()
        assertTrue(result is WrapResult.Refused, "$result")
        assertTrue((result as WrapResult.Refused).reason.contains("not a symlink"), result.reason)
        assertFalse(rig.cmd.isSymbolicLink(), "the real file must survive a refused wrap untouched")
    }

    @Test
    fun `wrap refuses blind when there is nothing to preserve`(@TempDir home: Path) {
        val rig = WrapRig(home)
        val result = rig.head.wrap()
        assertTrue(result is WrapResult.Refused, "$result")
        assertFalse(rig.cmd.exists(), "a refused wrap creates nothing")
    }

    @Test
    fun `wrap refuses a dangling shadowed link rather than wrapping over a broken install`(@TempDir home: Path) {
        val rig = WrapRig(home)
        Files.createSymbolicLink(rig.cmd, home.resolve("nowhere"))
        val result = rig.head.wrap()
        assertTrue(result is WrapResult.Refused, "$result")
        assertTrue((result as WrapResult.Refused).reason.contains("dangling"), result.reason)
    }

    @Test
    fun `unwrap restores the exact prior symlink target and clears state, the vanilla state untouched`(
        @TempDir home: Path,
    ) {
        val rig = WrapRig(home)
        rig.linkCmdToReal()
        val vanilla = VanillaState(home)

        assertTrue(rig.head.wrap() is WrapResult.Ok)
        val result = rig.head.unwrap()
        assertTrue(result is UnwrapResult.Ok, "$result")
        result as UnwrapResult.Ok
        assertEquals("separate", result.status.mode)

        assertTrue(rig.cmd.isSymbolicLink())
        assertEquals(rig.realBinary, rig.cmd.readSymbolicLink())
        vanilla.assertUntouched()
        assertTrue(rig.stateStore.read() is StoredWrap.Absent, "the state is cleared")
    }

    @Test
    fun `wrap creates nothing in a home that has no claude state at all`(@TempDir home: Path) {
        val rig = WrapRig(home)
        rig.linkCmdToReal()
        assertTrue(rig.head.wrap() is WrapResult.Ok)
        assertFalse(home.resolve(".claude").exists(), "no ~/.claude was made")
        assertFalse(home.resolve(".claude.json").exists(), "no ~/.claude.json was made")
        assertTrue(rig.head.unwrap() is UnwrapResult.Ok)
        assertFalse(home.resolve(".claude").exists())
    }

    @Test
    fun `unwrap puts back the backups of a wrap that recorded none`(@TempDir home: Path) {
        val rig = WrapRig(home)
        rig.linkCmdToReal()
        assertTrue(rig.head.wrap() is WrapResult.Ok)
        val vanilla = home.resolve(".claude").createDirectories()
        val backup = vanilla.resolve("settings.json.splice-wrap-backup-1")
        backup.writeText("""{"before":"wrap"}""")
        vanilla.resolve("settings.json").writeText("""{"rewritten":"by the old wrap"}""")
        rig.stateStore.write(rig.stateStore.read().state!!.copy(settingsBackupPath = backup.toString()))

        assertTrue(rig.head.unwrap() is UnwrapResult.Ok)
        assertEquals("""{"before":"wrap"}""", vanilla.resolve("settings.json").readText())
        assertFalse(backup.exists(), "the backup was moved back, not copied")
    }

    @Test
    fun `unwrap refuses when claude is not currently wrapped`(@TempDir home: Path) {
        val rig = WrapRig(home)
        rig.linkCmdToReal()
        val result = rig.head.unwrap()
        assertTrue(result is UnwrapResult.Refused, "$result")
    }

    @Test
    fun `unwrap refuses honestly when the state file is missing, without touching the shim link`(
        @TempDir home: Path,
    ) {
        val rig = WrapRig(home)
        rig.linkCmdToReal()
        assertTrue(rig.head.wrap() is WrapResult.Ok)
        Files.delete(home.resolve("state/claude-head-wrap.json"))
        val result = rig.head.unwrap()
        assertTrue(result is UnwrapResult.Refused, "$result")
        assertEquals("wrapped", rig.head.status().mode, "a refused unwrap must not have touched the live link")
    }

    @Test
    fun `a state file that cannot be used while the shim stands in refuses the launch and unwrap, and never reads as unwrapped`(
        @TempDir home: Path,
    ) {
        val rig = WrapRig(home)
        rig.linkCmdToReal()
        assertTrue(rig.head.wrap() is WrapResult.Ok)
        val stateFile = home.resolve("state/claude-head-wrap.json")
        listOf("[]", "not json {{{", "{}").forEach { body ->
            stateFile.writeText(body)

            assertEquals(null, rig.head.realBinaryPath(), "no binary can be named: $body")
            val claude = rig.head.claude()
            assertTrue(
                claude is ClaudeToRun.Refused && claude.reason.contains(stateFile.toString()),
                "the launch is refused, naming the file: $claude",
            )
            val unwrap = rig.head.unwrap()
            assertTrue(unwrap is UnwrapResult.Refused && unwrap.reason.contains(stateFile.toString()), "$unwrap")
            val reconcile = rig.head.reconcile()
            val named = reconcile is ReconcileResult.Waiting && reconcile.reason.contains(stateFile.toString())
            assertTrue(named, "$reconcile")
            assertEquals("wrapped", rig.head.status().mode, "nothing touched the live link: $body")
        }
    }

    @Test
    fun `a state file that is missing while the shim stands in refuses a launch too, since bare claude is the shim`(
        @TempDir home: Path,
    ) {
        val rig = WrapRig(home)
        rig.linkCmdToReal()
        assertTrue(rig.head.wrap() is WrapResult.Ok)
        Files.delete(home.resolve("state/claude-head-wrap.json"))

        val claude = rig.head.claude()
        assertTrue(claude is ClaudeToRun.Refused && claude.reason.contains("is missing"), "$claude")
    }

    @Test
    fun `without the shim a bad or missing state file refuses nothing, since bare claude is the real one`(
        @TempDir home: Path,
    ) {
        val rig = WrapRig(home)
        rig.linkCmdToReal()
        assertEquals(ClaudeToRun.ThroughPath, rig.head.claude())

        val stateFile = home.resolve("state/claude-head-wrap.json")
        stateFile.parent.createDirectories()
        stateFile.writeText("[]")
        assertEquals(ClaudeToRun.ThroughPath, rig.head.claude())
        assertEquals(null, rig.head.realBinaryPath())
    }
}
