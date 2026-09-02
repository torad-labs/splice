// DR-167: DoctorCommandTest's only derived-env fixture names the head AND the provider `openrouter`,
// so a doctor that derives <KEY>_API_KEY from the PROVIDER key reads identically to one deriving
// from the HEAD key — the conflation is invisible (mutating headAuthOf(key, …) to
// headAuthOf(provider, …) left every arm green). This class separates the two for good: head `fast`
// on provider `openrouter`, no auth.env, only FAST_API_KEY supplied. The daemon wires the derived
// default from the head key (effectiveApiKeyEnv), so the only honest variable is FAST_API_KEY.
//
// Its own file rather than another arm on DoctorCommandTest: that class sits at detekt's LargeClass
// ceiling, and the rig below is the same hermetic tmp-tree + fake-PATH shape it uses.
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

class DoctorHeadKeyTest {

    private fun runDoctor(env: Map<String, String?>): Pair<Boolean, String> {
        val reader: (String) -> String? = { env[it] }
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        return try {
            DoctorCommand().doctor(reader) to buffer.toString(Charsets.UTF_8)
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

    // Pinned to an empty temp state dir and a free control port (as DoctorCommandTest.hermetic) so an
    // ambient local daemon can never inject a split-brain FAIL or a real mgmt-key into this run.
    private fun env(tmp: Path, bin: Path, share: Path, extra: Map<String, String?>): Map<String, String?> = mapOf(
        "XDG_CONFIG_HOME" to tmp.resolve("config").toString(),
        "SPLICE_BIN_DIR" to bin.toString(),
        "SPLICE_SHARE_DIR" to share.toString(),
        "PATH" to bin.toString(),
        "CLAUDEX_STATE_DIR" to Files.createDirectories(tmp.resolve("state")).toString(),
        "SPLICE_CONTROL_PORT" to ServerSocket(0).use { it.localPort }.toString(),
    ) + extra

    // Head key `fast` ≠ provider key `openrouter`; the api-key provider omits `env`, so auth resolves
    // through the derived <KEY>_API_KEY default — and only the HEAD-derived FAST_API_KEY is supplied.
    private val starterTomlHeadNotProvider = """
        [daemon]
        control_port = 4499

        [providers.openrouter]
        dialect = "openai-chat"
        base_url = "https://openrouter.example/api/v1"
        auth = { kind = "api-key" }

        [[providers.openrouter.models]]
        id = "m"
        context_window = 200000

        [heads.fast]
        provider = "openrouter"
        port = 4501
        discovery_prefix = "claude-fast--"
        pinned_model = "m"

        [heads.fast.claude]
        command = "claude-fast"
    """.trimIndent()

    // OPENROUTER_API_KEY is deliberately absent, so a provider-keyed headAuthOf reports this healthy
    // head as unauthed (FAIL) and names the wrong variable.
    @Test
    fun `the derived KEY_API_KEY comes from the head key, never the provider key - DR-167`() {
        val tmp = Files.createTempDirectory("doctor-head-not-provider")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val share = Files.createDirectories(tmp.resolve("share"))
        val configDir = Files.createDirectories(tmp.resolve("config").resolve("splice"))
        Files.writeString(configDir.resolve("splice.toml"), starterTomlHeadNotProvider)
        val shim = share.resolve("splice-launch")
        Files.writeString(shim, "#!/usr/bin/env bash\nSPLICE_SHIM_VERSION=\"$SHIM_VERSION\"\n")
        shim.toFile().setExecutable(true)
        fakeBinaries(bin, "claude", "node", "python3", "curl", "bash")
        Files.createSymbolicLink(bin.resolve("claude-fast"), shim)
        Files.createSymbolicLink(bin.resolve("splice"), shim)

        val (ok, out) = runDoctor(env(tmp, bin, share, mapOf("FAST_API_KEY" to "k")))
        assertTrue(ok, "expected the head-derived FAST_API_KEY to satisfy auth:\n$out")
        assertTrue(out.contains("FAST_API_KEY is set"), out)
        assertFalse(out.contains("OPENROUTER_API_KEY"), out)
    }
}
