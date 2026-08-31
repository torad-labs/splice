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
import java.nio.file.attribute.PosixFilePermissions

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

    // DR-69 redo (codex replay): the exists() pre-gate read a denied config parent as Absent,
    // and doctor said "no topology yet — splice init" over a PRESENT operator config. Only
    // proven absence is first-run; indeterminate access is a Broken FAIL naming the path.
    @Test
    fun `an inaccessible config parent reports broken, never first-run - DR-69`() {
        val tmp = Files.createTempDirectory("doctor-dr69")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val share = Files.createDirectories(tmp.resolve("share"))
        val configDir = Files.createDirectories(tmp.resolve("config").resolve("splice"))
        Files.writeString(configDir.resolve("splice.toml"), starterToml)
        Files.setPosixFilePermissions(configDir, PosixFilePermissions.fromString("---------"))
        val output = try {
            runDoctor(env(tmp, bin, share, hermetic(tmp))).second
        } finally {
            Files.setPosixFilePermissions(configDir, PosixFilePermissions.fromString("rwx------"))
        }
        assertTrue(output.contains("does not parse"), output)
        assertFalse(output.contains("no topology yet"), output)
    }

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
        command = "claude-openrouter"
    """.trimIndent()

    // Same starter, but the api-key provider omits an explicit `env` — auth resolves through the
    // derived <KEY>_API_KEY default (head key `openrouter` → OPENROUTER_API_KEY).
    private val starterTomlDerivedEnv = starterToml.replace(
        """auth = { kind = "api-key", env = "OPENROUTER_API_KEY" }""",
        """auth = { kind = "api-key" }""",
    )

    // JW-13: a second head copy-pasted onto the same port (4501) — the most likely TOML mistake.
    private val starterTomlDupPort = starterToml + "\n" + """
        [heads.openrouter2]
        provider = "openrouter"
        port = 4501
        discovery_prefix = "claude-openrouter2--"
        pinned_model = "m"

        [heads.openrouter2.claude]
        command = "claude-openrouter2"
    """.trimIndent()

    // A client-auth head holds NO splice credential by design (campaign claude-head). Falling
    // through to the api-key branch made doctor FAIL a working head and offer
    // `export CLAUDE-MAX_API_KEY=…` as the fix — a name `export` cannot even accept.
    private val clientAuthToml = """
        [daemon]
        control_port = 4499

        [providers.anthropic]
        dialect = "anthropic-passthrough"
        base_url = "https://api.anthropic.com"
        auth = { kind = "client" }

        [[providers.anthropic.models]]
        id = "claude-fable-5"
        context_window = 200000

        [heads.claude-splice]
        provider = "anthropic"
        port = 4599
        discovery_prefix = "claude-splice--"
        pinned_model = "claude-fable-5"

        [heads.claude-splice.claude]
        command = "claude-splice"
    """.trimIndent()

    @Test
    fun `a client-auth head reads as configured, never as a missing api key`() {
        val tmp = Files.createTempDirectory("doctor-client-auth")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val share = Files.createDirectories(tmp.resolve("share"))
        val configDir = Files.createDirectories(tmp.resolve("config").resolve("splice"))
        Files.writeString(configDir.resolve("splice.toml"), clientAuthToml)
        val shim = share.resolve("splice-launch")
        Files.writeString(shim, "#!/usr/bin/env bash\nSPLICE_SHIM_VERSION=\"$SHIM_VERSION\"\n")
        shim.toFile().setExecutable(true)
        fakeBinaries(bin, "claude", "node", "python3", "curl", "bash")
        Files.createSymbolicLink(bin.resolve("claude-splice"), shim)
        Files.createSymbolicLink(bin.resolve("splice"), shim)

        // no credential env at all — the caller supplies it per request, not the environment
        val (ok, out) = runDoctor(env(tmp, bin, share, hermetic(tmp, emptyMap())))
        assertTrue(ok, "a client-auth head must not fail doctor:\n$out")
        assertTrue(out.contains("client-native"), out)
        assertFalse(out.contains("CLAUDE-MAX_API_KEY"), "never offer an illegal env var name:\n$out")
        assertFalse(out.contains("export CLAUDE"), out)
    }

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
        Files.createSymbolicLink(bin.resolve("claude-openrouter"), shim)
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
        Files.createSymbolicLink(bin.resolve("claude-openrouter"), shim)
        Files.createSymbolicLink(bin.resolve("splice"), shim)

        val (ok, out) = runDoctor(env(tmp, bin, share, hermetic(tmp, mapOf("OPENROUTER_API_KEY" to "k"))))
        assertTrue(ok, "expected no failures:\n$out")
        assertTrue(out.contains("OPENROUTER_API_KEY is set"), out)
        assertTrue(out.contains("openrouter → claude-openrouter"), out)
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
        Files.createSymbolicLink(bin.resolve("claude-openrouter"), shim)
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
        Files.writeString(bin.resolve("claude-openrouter"), "#!/bin/sh\necho foreign\n")
        bin.resolve("claude-openrouter").toFile().setExecutable(true)
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
        Files.createSymbolicLink(bin.resolve("claude-openrouter"), shim)
        Files.createSymbolicLink(bin.resolve("splice"), shim)

        val (ok, out) = runDoctor(env(tmp, bin, share))
        assertFalse(ok)
        assertTrue(out.contains("OPENROUTER_API_KEY is not set"), out)
        assertTrue(out.contains("export OPENROUTER_API_KEY"), out)
    }

    @Test
    fun `a daemon with failed heads is a FAIL, never everything-checks-out - JW-02`() {
        val tmp = Files.createTempDirectory("doctor-degraded")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val share = Files.createDirectories(tmp.resolve("share"))
        val configDir = Files.createDirectories(tmp.resolve("config").resolve("splice"))
        Files.writeString(configDir.resolve("splice.toml"), starterToml)
        val shim = share.resolve("splice-launch")
        Files.writeString(shim, "#!/usr/bin/env bash\nSPLICE_SHIM_VERSION=\"$SHIM_VERSION\"\n")
        shim.toFile().setExecutable(true)
        fakeBinaries(bin, "claude", "node", "python3", "curl", "bash")
        Files.createSymbolicLink(bin.resolve("claude-openrouter"), shim)
        Files.createSymbolicLink(bin.resolve("splice"), shim)

        // A live /health reporting a degraded boot: 3 configured, 1 ready, 2 dead. Pre-fix the
        // doctor extracted only `version` and closed with "Everything checks out."
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val healthJson =
            """{"ok":true,"version":"${splice.core.GATEWAY_VERSION}","heads":3,"readyHeads":1,"failedHeads":2}"""
        server.createContext("/health") { ex ->
            val bytes = healthJson.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            // state dir carries a mgmt-key so the running-daemon key check stays green — this
            // test is about the HEAD rows.
            val state = Files.createDirectories(tmp.resolve("state"))
            Files.writeString(state.resolve("mgmt-key"), "k\n")
            val envMap = env(
                tmp,
                bin,
                share,
                mapOf(
                    "OPENROUTER_API_KEY" to "k",
                    "CLAUDEX_STATE_DIR" to state.toString(),
                    "SPLICE_CONTROL_PORT" to server.address.port.toString(),
                ),
            )
            val (ok, out) = runDoctor(envMap)
            assertTrue(!ok, "failed heads must be a doctor FAILURE:\n$out")
            assertTrue(out.contains("2 of 3 head(s) FAILED to start"), out)
            assertTrue(out.contains("splice restart"), out)
            assertTrue(!out.contains("Everything checks out"), out)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an edited splice_toml shows a stale-topology WARN with the restart fix - JW-04`() {
        val tmp = Files.createTempDirectory("doctor-stale-topo")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val share = Files.createDirectories(tmp.resolve("share"))
        val configDir = Files.createDirectories(tmp.resolve("config").resolve("splice"))
        Files.writeString(configDir.resolve("splice.toml"), starterToml)
        val shim = share.resolve("splice-launch")
        Files.writeString(shim, "#!/usr/bin/env bash\nSPLICE_SHIM_VERSION=\"$SHIM_VERSION\"\n")
        shim.toFile().setExecutable(true)
        fakeBinaries(bin, "claude", "node", "python3", "curl", "bash")
        Files.createSymbolicLink(bin.resolve("claude-openrouter"), shim)
        Files.createSymbolicLink(bin.resolve("splice"), shim)

        // A healthy daemon whose booted digest does NOT match the file on disk (the operator
        // edited splice.toml after boot). Pre-fix: no consumer ever compared them.
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val healthJson =
            """{"ok":true,"version":"${splice.core.GATEWAY_VERSION}","heads":1,"readyHeads":1,""" +
                """"failedHeads":0,"topologyDigest":"digest-of-what-it-booted-with","topologyStale":true}"""
        server.createContext("/health") { ex ->
            val bytes = healthJson.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val state = Files.createDirectories(tmp.resolve("state"))
            Files.writeString(state.resolve("mgmt-key"), "k\n")
            val (ok, out) = runDoctor(
                env(
                    tmp,
                    bin,
                    share,
                    mapOf(
                        "OPENROUTER_API_KEY" to "k",
                        "CLAUDEX_STATE_DIR" to state.toString(),
                        "SPLICE_CONTROL_PORT" to server.address.port.toString(),
                    ),
                ),
            )
            assertTrue(ok, "a stale topology is a WARN, not a failure:\n$out")
            assertTrue(out.contains("running topology is stale"), out)
            assertTrue(out.contains("splice restart"), out)
            assertTrue(!out.contains("Everything checks out"), out)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `doctor names the logs path in the logs dir, not the state dir - JW-08`() {
        val tmp = Files.createTempDirectory("doctor-logs")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val share = Files.createDirectories(tmp.resolve("share"))
        fakeBinaries(bin, "claude", "node", "python3", "curl", "bash")
        val (_, out) = runDoctor(env(tmp, bin, share, hermetic(tmp)))
        // the row must point at <root>/logs/daemon.log and mention the verb — NOT the state dir
        assertTrue(out.contains("logs/daemon.log"), out)
        assertTrue(out.contains("splice logs"), out)
    }

    @Test
    fun `two heads on one port is a config FAIL naming both - JW-13`() {
        val tmp = Files.createTempDirectory("doctor-dupport")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val share = Files.createDirectories(tmp.resolve("share"))
        val configDir = Files.createDirectories(tmp.resolve("config").resolve("splice"))
        Files.writeString(configDir.resolve("splice.toml"), starterTomlDupPort)
        fakeBinaries(bin, "claude", "node", "python3", "curl", "bash")
        val (ok, out) = runDoctor(env(tmp, bin, share, hermetic(tmp, mapOf("OPENROUTER_API_KEY" to "k"))))
        assertTrue(!ok, "a duplicate port must be a doctor FAILURE:\n$out")
        assertTrue(out.contains("port 4501 is claimed by"), out)
        assertTrue(out.contains("openrouter") && out.contains("openrouter2"), out)
        assertTrue(out.contains("change one head's port"), out)
    }

    @Test
    fun `an unwritable state dir is a FAIL with a chmod fix, and the probe is cleaned up - JW-17`() {
        val tmp = Files.createTempDirectory("doctor-unwritable")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val share = Files.createDirectories(tmp.resolve("share"))
        fakeBinaries(bin, "claude", "node", "python3", "curl", "bash")
        val state = Files.createDirectories(tmp.resolve("state"))
        // 0500: readable + executable, NOT writable. Skip if the test user is root (chmod is
        // advisory for uid 0).
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("user.name") == "root")
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("r-x------"))
        try {
            val env = mapOf(
                "XDG_CONFIG_HOME" to tmp.resolve("config").toString(),
                "SPLICE_BIN_DIR" to bin.toString(),
                "SPLICE_SHARE_DIR" to share.toString(),
                "PATH" to bin.toString(),
                "CLAUDEX_STATE_DIR" to state.toString(),
                "SPLICE_CONTROL_PORT" to ServerSocket(0).use { it.localPort }.toString(),
                "OPENROUTER_API_KEY" to "k",
            )
            val (ok, out) = runDoctor(env)
            assertTrue(!ok, "an unwritable state dir must be a doctor FAILURE:\n$out")
            assertTrue(out.contains("state dir") && out.contains("not writable"), out)
            assertTrue(out.contains("chmod u+rwx"), out)
            // non-mutating in spirit: no probe file survives
            val noProbe = Files.list(state).use { s ->
                s.noneMatch { it.fileName.toString().contains("probe") }
            }
            assertTrue(noProbe, "probe leaked")
        } finally {
            Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"))
        }
    }
}
