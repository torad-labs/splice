// NEW: V4-129 (FEATURES.md 4.12: "Doctor reports the mode") — DoctorProbes' claude-head check,
// pinned directly (not through the full prerequisiteChecks(), which also runs the real java/claude/
// gh probes — slow and environment-dependent, and not what this row's contract is about).
package splice.diagnostics.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.wrap.WrapStateStore
import splice.client.wrap.WrappedHead
import splice.core.config.InstallPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class DoctorClaudeHeadCheckTest {

    private fun probes(home: Path, bin: Path, share: Path) = DoctorProbes(
        runningJar = DoctorTestPorts.noJar,
        wrappedHead = WrappedHead(
            home = home,
            installPaths = InstallPaths(binOverride = bin, shareOverride = share),
            stateStore = WrapStateStore(file = home.resolve("state/claude-head-wrap.json")),
        ),
    )

    @Test
    fun `separate mode is reported by name when claude on PATH is untouched`(@TempDir home: Path) {
        val bin = home.resolve("bin").also { Files.createDirectories(it) }
        val share = home.resolve("share").also { Files.createDirectories(it) }
        val real = bin.resolve("claude").also { it.writeText("#!/bin/sh\n") }
        val check = probes(home, bin, share).claudeHeadModeCheck()
        assertEquals("claude-head", check.name)
        assertEquals(CheckStatus.INFO, check.status)
        assertTrue(check.detail.startsWith("separate"), check.detail)
        assertTrue(check.detail.contains(real.toRealPath().toString()), check.detail)
    }

    @Test
    fun `wrapped mode is reported by name after a real wrap`(@TempDir home: Path) {
        val bin = home.resolve("bin").also { Files.createDirectories(it) }
        val share = home.resolve("share").also { Files.createDirectories(it) }
        bin.resolve("claude").also { Files.createSymbolicLink(it, home.resolve("real").also { r -> r.writeText("x") }) }
        val shim = share.resolve("splice-launch").also { it.writeText("shim") }
        Files.delete(bin.resolve("claude"))
        Files.createSymbolicLink(bin.resolve("claude"), shim)
        val check = probes(home, bin, share).claudeHeadModeCheck()
        assertEquals("wrapped", check.detail.substringBefore(" —"))
    }
}
