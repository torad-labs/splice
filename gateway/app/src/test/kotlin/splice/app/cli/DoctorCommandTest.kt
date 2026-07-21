// doctor: hermetic scenarios over a tmp install tree + fake PATH binaries. Assertions avoid the
// daemon section (a live local daemon must not flip a test) — daemon state can never be a FAIL,
// so the return value stays deterministic.
package splice.app.cli

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.SHIM_VERSION
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

// Generous relative to OVERALL_BOUND_SECONDS (PROBE_SECONDS * 3): proves a hang is bounded at
// all, not a tight race against it.
private const val HANG_BOUND_SECONDS = 20.0
private const val NANOS_PER_SECOND = 1_000_000_000.0

class DoctorCommandTest {

    private fun runDoctor(env: Map<String, String?>): Pair<Boolean, String> {
        val reader: (String) -> String? = { env[it] }
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        return try {
            doctor(reader) to buffer.toString(Charsets.UTF_8)
        } finally {
            System.setOut(original)
        }
    }

    private fun fakeBinaries(dir: Path, vararg names: String) {
        names.forEach { name ->
            val script = dir.resolve(name)
            Files.writeString(script, "#!/bin/sh\necho fake-$name 1.0\n")
            script.toFile().setExecutable(true)
        }
    }

    private fun env(tmp: Path, bin: Path, share: Path, extra: Map<String, String?> = emptyMap()) = mapOf(
        "XDG_CONFIG_HOME" to tmp.resolve("config").toString(),
        "SPLICE_BIN_DIR" to bin.toString(),
        "SPLICE_SHARE_DIR" to share.toString(),
        "PATH" to bin.toString(),
    ) + extra

    // Pin the daemon section to an empty temp state dir and a free (nothing-listening) control port
    // so an ambient local daemon can never inject a split-brain FAIL or a real mgmt-key into a
    // hermetic run — the port resolves via the fake env, StatePaths reads CLAUDEX_STATE_DIR.
    private fun hermetic(tmp: Path, extra: Map<String, String?> = emptyMap()): Map<String, String?> = mapOf(
        "CLAUDEX_STATE_DIR" to Files.createDirectories(tmp.resolve("state")).toString(),
        "SPLICE_CONTROL_PORT" to ServerSocket(0).use { it.localPort }.toString(),
    ) + extra

    private val starterToml = """
        [daemon]
        control_port = 4499

        [providers.openrouter]
        dialect = "openai-chat"
        base_url = "https://openrouter.example/api/v1"
        auth = { kind = "api-key", env = "OPENROUTER_API_KEY" }

        [[providers.openrouter.models]]
        id = "m"
        context_window = 200000

        [heads.openrouter]
        provider = "openrouter"
        port = 4501
        discovery_prefix = "claude-openrouter--"
        pinned_model = "m"

        [heads.openrouter.claude]
        command = "claudeor"
    """.trimIndent()

    // Same starter, but the api-key provider omits an explicit `env` — auth resolves through the
    // derived <KEY>_API_KEY default (head key `openrouter` → OPENROUTER_API_KEY).
    private val starterTomlDerivedEnv = starterToml.replace(
        """auth = { kind = "api-key", env = "OPENROUTER_API_KEY" }""",
        """auth = { kind = "api-key" }""",
    )

    @Test
    fun `an api-key head with no explicit env resolves the derived KEY_API_KEY`() {
        val tmp = Files.createTempDirectory("doctor-derived")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val share = Files.createDirectories(tmp.resolve("share"))
        val configDir = Files.createDirectories(tmp.resolve("config").resolve("splice"))
        Files.writeString(configDir.resolve("splice.toml"), starterTomlDerivedEnv)
        val shim = share.resolve("splice-launch")
        Files.writeString(shim, "#!/usr/bin/env bash\nSPLICE_SHIM_VERSION=\"$SHIM_VERSION\"\n")
        shim.toFile().setExecutable(true)
        fakeBinaries(bin, "claude", "node", "python3", "curl", "bash")
        Files.createSymbolicLink(bin.resolve("claudeor"), shim)
        Files.createSymbolicLink(bin.resolve("splice"), shim)

        val (ok, out) = runDoctor(env(tmp, bin, share, hermetic(tmp, mapOf("OPENROUTER_API_KEY" to "k"))))
        assertTrue(ok, "expected the derived OPENROUTER_API_KEY to satisfy auth:\n$out")
        assertTrue(out.contains("OPENROUTER_API_KEY is set"), out)
    }

    @Test
    fun `a complete install with auth passes`() {
        val tmp = Files.createTempDirectory("doctor-green")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val share = Files.createDirectories(tmp.resolve("share"))
        val configDir = Files.createDirectories(tmp.resolve("config").resolve("splice"))
        Files.writeString(configDir.resolve("splice.toml"), starterToml)
        val shim = share.resolve("splice-launch")
        Files.writeString(shim, "#!/usr/bin/env bash\nSPLICE_SHIM_VERSION=\"$SHIM_VERSION\"\n")
        shim.toFile().setExecutable(true)
        fakeBinaries(bin, "claude", "node", "python3", "curl", "bash")
        Files.createSymbolicLink(bin.resolve("claudeor"), shim)
        Files.createSymbolicLink(bin.resolve("splice"), shim)

        val (ok, out) = runDoctor(env(tmp, bin, share, hermetic(tmp, mapOf("OPENROUTER_API_KEY" to "k"))))
        assertTrue(ok, "expected no failures:\n$out")
        assertTrue(out.contains("OPENROUTER_API_KEY is set"), out)
        assertTrue(out.contains("openrouter → claudeor"), out)
    }

    @Test
    fun `an absent mgmt-key with the daemon stopped is an INFO, never a failure`() {
        val tmp = Files.createTempDirectory("doctor-mgmtkey")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val share = Files.createDirectories(tmp.resolve("share"))
        val configDir = Files.createDirectories(tmp.resolve("config").resolve("splice"))
        Files.writeString(configDir.resolve("splice.toml"), starterToml)
        val shim = share.resolve("splice-launch")
        Files.writeString(shim, "#!/usr/bin/env bash\nSPLICE_SHIM_VERSION=\"$SHIM_VERSION\"\n")
        shim.toFile().setExecutable(true)
        fakeBinaries(bin, "claude", "node", "python3", "curl", "bash")
        Files.createSymbolicLink(bin.resolve("claudeor"), shim)
        Files.createSymbolicLink(bin.resolve("splice"), shim)

        // Empty state dir (hermetic) → no mgmt-key file; free control port → daemon reads as stopped.
        val (ok, out) = runDoctor(env(tmp, bin, share, hermetic(tmp, mapOf("OPENROUTER_API_KEY" to "k"))))
        assertTrue(ok, "a stopped daemon with no minted key must not fail doctor:\n$out")
        assertTrue(out.contains("minted on first launch"), out)
        assertFalse(out.contains("admin endpoints will 401"), out)
    }

    @Test
    fun `a fresh machine names every fix`() {
        val tmp = Files.createTempDirectory("doctor-fresh")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val share = Files.createDirectories(tmp.resolve("share"))

        val (ok, out) = runDoctor(env(tmp, bin, share))
        assertFalse(ok)
        assertTrue(out.contains("no topology yet"), out)
        assertTrue(out.contains("splice init"), out)
        assertTrue(out.contains("launch shim missing"), out)
        assertTrue(out.contains("splice install --all"), out) // the 'splice' wrapper itself
        assertTrue(out.contains("install bash with your package manager"), out)
    }

    @Test
    fun `a foreign non-symlink wrapper file gets a move-aside fix`() {
        val tmp = Files.createTempDirectory("doctor-foreignwrapper")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val share = Files.createDirectories(tmp.resolve("share"))
        val configDir = Files.createDirectories(tmp.resolve("config").resolve("splice"))
        Files.writeString(configDir.resolve("splice.toml"), starterToml)
        val shim = share.resolve("splice-launch")
        Files.writeString(shim, "#!/usr/bin/env bash\nSPLICE_SHIM_VERSION=\"$SHIM_VERSION\"\n")
        shim.toFile().setExecutable(true)
        fakeBinaries(bin, "claude", "node", "python3", "curl", "bash")
        Files.writeString(bin.resolve("claudeor"), "#!/bin/sh\necho foreign\n")
        bin.resolve("claudeor").toFile().setExecutable(true)
        Files.createSymbolicLink(bin.resolve("splice"), shim)

        val (_, out) = runDoctor(env(tmp, bin, share, mapOf("OPENROUTER_API_KEY" to "k")))
        assertTrue(out.contains("not a splice-managed symlink"), out)
        assertTrue(out.contains("move the foreign file aside, then: splice install --all"), out)
    }

    @Test
    fun `a probe that blocks on its inherited stdin cannot hang doctor`() {
        val tmp = Files.createTempDirectory("doctor-hang")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val share = Files.createDirectories(tmp.resolve("share"))
        // `cat` with a never-closed, never-written stdin pipe blocks forever — exactly the
        // inherited-stdin hang the restructured capturedVersion()/probe pool must survive.
        Files.writeString(bin.resolve("claude"), "#!/bin/sh\ncat\n")
        bin.resolve("claude").toFile().setExecutable(true)
        fakeBinaries(bin, "node", "python3", "curl", "bash")

        val start = System.nanoTime()
        val (_, out) = runDoctor(env(tmp, bin, share))
        val elapsedSeconds = (System.nanoTime() - start) / NANOS_PER_SECOND

        assertTrue(elapsedSeconds < HANG_BOUND_SECONDS, "doctor took ${elapsedSeconds}s, expected a bound:\n$out")
        assertTrue(out.contains("probe timed out"), out)
    }

    @Test
    fun `a broken topology reports the parse error with a fix`() {
        val tmp = Files.createTempDirectory("doctor-broken")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val share = Files.createDirectories(tmp.resolve("share"))
        val configDir = Files.createDirectories(tmp.resolve("config").resolve("splice"))
        Files.writeString(configDir.resolve("splice.toml"), "[daemon\ncontrol_port = nope")

        val (ok, out) = runDoctor(env(tmp, bin, share))
        assertFalse(ok)
        assertTrue(out.contains("does not parse"), out)
    }

    @Test
    fun `a missing api key is the failure and the fix names the export`() {
        val tmp = Files.createTempDirectory("doctor-nokey")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val share = Files.createDirectories(tmp.resolve("share"))
        val configDir = Files.createDirectories(tmp.resolve("config").resolve("splice"))
        Files.writeString(configDir.resolve("splice.toml"), starterToml)
        val shim = share.resolve("splice-launch")
        Files.writeString(shim, "#!/usr/bin/env bash\nSPLICE_SHIM_VERSION=\"$SHIM_VERSION\"\n")
        shim.toFile().setExecutable(true)
        fakeBinaries(bin, "claude", "node", "python3", "curl", "bash")
        Files.createSymbolicLink(bin.resolve("claudeor"), shim)
        Files.createSymbolicLink(bin.resolve("splice"), shim)

        val (ok, out) = runDoctor(env(tmp, bin, share))
        assertFalse(ok)
        assertTrue(out.contains("OPENROUTER_API_KEY is not set"), out)
        assertTrue(out.contains("export OPENROUTER_API_KEY"), out)
    }
}
