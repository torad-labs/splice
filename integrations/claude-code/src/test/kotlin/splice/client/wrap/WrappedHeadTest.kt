// NEW: V4-129 — WrappedHead's wrap/unwrap orchestration: the shim swap, the shadowed-symlink
// preservation, and the settings.json/.claude.json backup+restore round trip. Every fixture is a
// real filesystem under @TempDir (no fakes for Files.* — the safety property under test IS the
// filesystem sequencing), with InstallPaths and WrapStateStore pointed at temp subdirectories so no
// test touches the real ~/.local/bin or ~/.claude-codey/state.
package splice.client.wrap

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudePolicy
import splice.client.MaterializeSpec
import splice.core.config.InstallPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeText

class WrappedHeadTest {

    private val optionsCache: JsonElement = buildJsonObject { put("cache", "claude-splice-models") }

    private fun spec() = MaterializeSpec(
        configDir = Path.of("/unused"), // WrappedHead.wrap overrides configDir unconditionally
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        availableModelIds = listOf("claude-fable-5"),
        defaultModel = "claude-fable-5",
        modelOptionsCache = optionsCache,
        statuslineCommand = "curl -sS :3096/statusline/claude-splice",
    )

    /** One rig: bin/, share/, a real "binary" file the pre-existing claude symlink points at, the
     *  shim file itself, and a WrappedHead wired to all of it plus a fixed clock. */
    private class Rig(home: Path) {
        val bin: Path = home.resolve("bin").createDirectories()
        val share: Path = home.resolve("share").createDirectories()
        private val versions: Path = home.resolve("versions").createDirectories()
        val realBinary: Path = versions.resolve("2.1.278").also {
            it.writeText("#!/bin/sh\n")
        }
        val cmd: Path = bin.resolve("claude")
        val shim: Path = share.resolve("splice-launch").also { it.writeText("#!/usr/bin/env bash\n") }
        val installPaths = InstallPaths(binOverride = bin, shareOverride = share)
        val stateStore = WrapStateStore(file = home.resolve("state").resolve("claude-head-wrap.json"))
        var clock = 1_000L
        val head = WrappedHead(
            home = home,
            installPaths = installPaths,
            stateStore = stateStore,
            now = { clock },
        )

        fun linkCmdToReal() = Files.createSymbolicLink(cmd, realBinary)
    }

    @Test
    fun `unwrapped status reports separate and the real path claude resolves to`(@TempDir home: Path) {
        val rig = Rig(home)
        rig.linkCmdToReal()
        val status = rig.head.status()
        assertEquals("separate", status.mode)
        assertEquals(rig.realBinary.toRealPath().toString(), status.resolvesTo)
        assertEquals(rig.shim.toString(), status.shimPath)
    }

    @Test
    fun `status with no claude on PATH at all reports separate with a null resolution`(@TempDir home: Path) {
        val rig = Rig(home)
        val status = rig.head.status()
        assertEquals("separate", status.mode)
        assertEquals(null, status.resolvesTo)
    }

    @Test
    fun `wrap preserves the shadowed symlink target, plants the real binary in state, and swaps the shim in`(
        @TempDir home: Path,
    ) {
        val rig = Rig(home)
        rig.linkCmdToReal()
        home.resolve(".claude").createDirectories().resolve("settings.json")
            .writeText("""{"theme":"dark","operatorOwnKey":"keepme"}""")

        val result = rig.head.wrap(spec())
        assertTrue(result is WrapResult.Ok, "$result")
        result as WrapResult.Ok
        assertEquals("wrapped", result.status.mode)
        assertEquals(rig.realBinary.toString(), result.status.realBinaryPath)

        // The command now resolves to the shim, not the real binary.
        assertTrue(rig.cmd.isSymbolicLink())
        assertEquals(rig.shim.toRealPath(), rig.cmd.toRealPath())

        // The shadowed target survived byte-for-byte in state, for unwrap to restore exactly.
        val state = rig.stateStore.read()!!
        assertEquals(rig.realBinary.toString(), state.shadowedSymlinkTarget)
        assertEquals(rig.realBinary.toString(), state.realBinaryPath)

        // The operator's own key survived the merge (policy forces settings.json's global layer in).
        val settings = home.resolve(".claude/settings.json").readText()
        assertTrue(settings.contains("keepme"), settings)
        assertTrue(settings.contains("\"enforceAvailableModels\": true"), settings)

        // A pre-existing settings.json was backed up byte-identical.
        assertTrue(Files.exists(Path.of(result.settingsBackupPath)))
        assertTrue(Path.of(result.settingsBackupPath).readText().contains("keepme"))
    }

    @Test
    fun `wrap refuses when the shim is not installed`(@TempDir home: Path) {
        val rig = Rig(home)
        rig.linkCmdToReal()
        Files.delete(rig.shim)
        val result = rig.head.wrap(spec())
        assertTrue(result is WrapResult.Refused, "$result")
    }

    @Test
    fun `wrap refuses when claude is already wrapped`(@TempDir home: Path) {
        val rig = Rig(home)
        rig.linkCmdToReal()
        assertTrue(rig.head.wrap(spec()) is WrapResult.Ok)
        val second = rig.head.wrap(spec())
        assertTrue(second is WrapResult.Refused, "$second")
        assertTrue((second as WrapResult.Refused).reason.contains("already wrapped"), second.reason)
    }

    @Test
    fun `wrap refuses a real file at the command path rather than overwriting it`(@TempDir home: Path) {
        val rig = Rig(home)
        rig.cmd.writeText("#!/bin/sh\necho not a symlink\n")
        val result = rig.head.wrap(spec())
        assertTrue(result is WrapResult.Refused, "$result")
        assertTrue((result as WrapResult.Refused).reason.contains("not a symlink"), result.reason)
        assertFalse(rig.cmd.isSymbolicLink(), "the real file must survive a refused wrap untouched")
    }

    @Test
    fun `wrap refuses blind when there is nothing to preserve`(@TempDir home: Path) {
        val rig = Rig(home)
        val result = rig.head.wrap(spec())
        assertTrue(result is WrapResult.Refused, "$result")
        assertFalse(rig.cmd.exists(), "a refused wrap creates nothing")
    }

    @Test
    fun `wrap refuses a dangling shadowed link rather than wrapping over a broken install`(@TempDir home: Path) {
        val rig = Rig(home)
        Files.createSymbolicLink(rig.cmd, home.resolve("nowhere"))
        val result = rig.head.wrap(spec())
        assertTrue(result is WrapResult.Refused, "$result")
        assertTrue((result as WrapResult.Refused).reason.contains("dangling"), result.reason)
    }

    @Test
    fun `unwrap restores the exact prior symlink target, the backed-up files, and clears state`(@TempDir home: Path) {
        val rig = Rig(home)
        rig.linkCmdToReal()
        home.resolve(".claude").createDirectories().resolve("settings.json").writeText("""{"before":"wrap"}""")

        assertTrue(rig.head.wrap(spec()) is WrapResult.Ok)
        val result = rig.head.unwrap()
        assertTrue(result is UnwrapResult.Ok, "$result")
        result as UnwrapResult.Ok
        assertEquals("separate", result.status.mode)

        assertTrue(rig.cmd.isSymbolicLink())
        assertEquals(rig.realBinary, rig.cmd.readSymbolicLink())
        assertEquals("""{"before":"wrap"}""", home.resolve(".claude/settings.json").readText())
        assertEquals(null, rig.stateStore.read())
    }

    @Test
    fun `unwrap restores absence when the vanilla dir had no claude json before wrap`(@TempDir home: Path) {
        val rig = Rig(home)
        rig.linkCmdToReal()
        assertFalse(home.resolve(".claude/.claude.json").exists())

        assertTrue(rig.head.wrap(spec()) is WrapResult.Ok)
        assertTrue(home.resolve(".claude/.claude.json").exists(), "wrap materializes a fresh one")
        assertTrue(rig.head.unwrap() is UnwrapResult.Ok)
        assertFalse(home.resolve(".claude/.claude.json").exists(), "unwrap restores the pre-wrap absence")
    }

    @Test
    fun `unwrap refuses when claude is not currently wrapped`(@TempDir home: Path) {
        val rig = Rig(home)
        rig.linkCmdToReal()
        val result = rig.head.unwrap()
        assertTrue(result is UnwrapResult.Refused, "$result")
    }

    @Test
    fun `unwrap refuses honestly when the state file is missing, without touching the shim link`(
        @TempDir home: Path,
    ) {
        val rig = Rig(home)
        rig.linkCmdToReal()
        assertTrue(rig.head.wrap(spec()) is WrapResult.Ok)
        Files.delete(home.resolve("state/claude-head-wrap.json"))
        val result = rig.head.unwrap()
        assertTrue(result is UnwrapResult.Refused, "$result")
        assertEquals("wrapped", rig.head.status().mode, "a refused unwrap must not have touched the live link")
    }
}
