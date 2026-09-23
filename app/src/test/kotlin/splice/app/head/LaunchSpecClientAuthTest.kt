// NEW (post-review, PR 99 finding 6): the two client-auth consumers must agree. ManagedHeadFactory
// derives forwardClientAuth structurally from the resolved ClientAuthProvider; LaunchSpecFactory
// consumes that same resolved flag to control ANTHROPIC_AUTH_TOKEN, ambient credential stripping,
// and /login. ProviderAssembly now rejects registered client auth on non-passthrough dialects before
// this factory is reached. The synthetic lower-level test remains to ensure LaunchSpecFactory never
// re-derives the flag from a raw auth.kind string if called directly.
package splice.app.head

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.auth.SignInPlanner
import splice.app.control.ControlServer
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderBuild
import splice.core.auth.CLIENT_AUTH_KIND
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.HeadModel
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.turn.WatchdogBudget
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.seconds

class LaunchSpecClientAuthTest {

    @Test
    fun `CLIENT_AUTH_KIND stays equal to AuthKind Client wire`() {
        assertEquals(AuthKind.Client.wire, CLIENT_AUTH_KIND)
    }

    private fun factory(tmp: Path, topology: Topology = Topology()): LaunchSpecFactory {
        val statePaths = StatePaths(baseOverride = tmp)
        val signInPlanner = SignInPlanner()
        return LaunchSpecFactory(
            topology = topology,
            signInPlanner = signInPlanner,
            mgmtKey = MgmtKey(statePaths),
            buildInputs = HeadBuildInputs(ConfigService(statePaths), signInPlanner),
        )
    }

    private fun build(tmp: Path, dialect: Dialect): ProviderBuild = ProviderBuild(
        key = "claude-splice",
        head = HeadConfig(
            provider = "anthropic",
            port = 3100,
            discoveryPrefix = "claude-splice--",
            pinnedModel = "m",
            claude = ClaudeWrapperConfig(command = "claude-splice", configDir = "$tmp/cfg"),
        ),
        // Synthetic divergent shape: ProviderAssembly rejects this registered tuple at runtime,
        // but direct factory tests can still construct it to pin resolved-flag ownership.
        providerCfg = ProviderConfig(
            dialect = dialect,
            baseUrl = "https://example.invalid",
            auth = AuthConfig(kind = CLIENT_AUTH_KIND),
        ),
        catalog = ModelCatalog(
            discoveryPrefix = "claude-splice--",
            models = listOf(ModelEntry(id = "m", contextWindow = 200_000)),
            defaultContextWindow = 200_000,
        ),
        watchdog = WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
        cfg = ConfigService(StatePaths(baseOverride = tmp)).getConfig(),
        loginCommand = "claude-splice login",
    )

    /** Lower-level ownership guard. Runtime assembly rejects this registered incompatible tuple,
     *  but the factory must still consume the resolved flag rather than reinterpret auth.kind. */
    @Test
    fun `a synthetic incompatible context cannot rederive native client auth`(@TempDir tmp: Path) {
        val spec = factory(tmp).launchSpecFor(
            build(tmp, Dialect.OPENAI_RESPONSES),
            controlPort = 3099,
            forwardClientAuth = false, // synthetic resolved input; runtime assembly rejects the tuple
        )
        assertFalse(
            spec.forwardClientAuth,
            "the recipe must follow its resolved input, not rederive from the declared auth.kind",
        )
    }

    // The client's request timeout is derived from the head's whole-turn cap, not a second number
    // kept by hand: 600s cap here -> 660s for Claude Code (2026-09-01 compaction client_aborts).
    @Test
    fun `the client's request timeout outlives the head's whole-turn cap`(@TempDir tmp: Path) {
        val spec = factory(tmp).launchSpecFor(build(tmp, Dialect.OPENAI_RESPONSES), 3099, forwardClientAuth = false)
        assertEquals(600_000L + 60_000L, spec.apiTimeoutMs)
    }

    /** Positive factory half: a resolved true input must be preserved rather than hardcoded false.
     *  AuthDialectCompatibilityBootTest covers the real passthrough arm derivation. */
    @Test
    fun `a resolved client-auth flag is preserved`(@TempDir tmp: Path) {
        val spec = factory(tmp).launchSpecFor(
            build(tmp, Dialect.ANTHROPIC_PASSTHROUGH),
            controlPort = 3099,
            forwardClientAuth = true,
        )
        assertTrue(
            spec.forwardClientAuth,
            "the anthropic-passthrough client arm forwards the caller's own credential — " +
                "its launch must keep that credential and keep /login open",
        )
    }

    @Test
    fun `model picker cache uses the effective per-head window`(@TempDir tmp: Path) {
        val declared = ModelEntry(id = "m", label = "Model", contextWindow = 256_000)
        val effective = declared.copy(contextWindow = 333_000)
        val base = build(tmp, Dialect.ANTHROPIC_PASSTHROUGH)
        val ctx = base.copy(
            providerCfg = base.providerCfg.copy(models = listOf(declared)),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-splice--",
                models = listOf(effective),
                defaultContextWindow = effective.contextWindow,
            ),
        )

        val spec = factory(tmp).launchSpecFor(ctx, 3099, forwardClientAuth = true)
        val cachedWindow = spec.modelOptionsCache.jsonArray.single().jsonObject
            .getValue("context_window").jsonPrimitive.long

        assertEquals(333_000, cachedWindow)
    }

    // The pinned row's window rides into the spec as a Long (an Int overflow regression). For one
    // morning on 2026-09-05 the spec carried a constant 1e6 instead, so that a TOML window edit
    // would reach a running session — and every session launched before it was scaled 2.5-3.7x
    // against a window its process never had, compacting at a third of its row's window forever.
    // A running session is reached through the window it reports on its status line instead.
    @Test
    fun `launch spec carries the pinned row's window as a Long`(@TempDir tmp: Path) {
        val window = Int.MAX_VALUE.toLong() + 1
        val model = ModelEntry(id = "m", contextWindow = window)
        val base = build(tmp, Dialect.ANTHROPIC_PASSTHROUGH)
        val ctx = base.copy(
            providerCfg = base.providerCfg.copy(models = listOf(model)),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-splice--",
                models = listOf(model),
                defaultContextWindow = window,
                pinnedModel = model.id,
            ),
        )

        val spec = factory(tmp).launchSpecFor(ctx, 3099, forwardClientAuth = true)

        assertEquals(ctx.catalog.clientLaunchWindow, spec.contextWindow, "the launch env is the catalog's number")
        assertEquals(window, spec.contextWindow, "the pinned row's own window, as a Long")
    }

    @Test
    fun `launch spec exposes labels only for the head catalog`(@TempDir tmp: Path) {
        val shown = ModelEntry(id = "shown", label = "Shown", contextWindow = 500_000)
        val hidden = ModelEntry(id = "hidden", label = "Hidden", contextWindow = 256_000)
        val base = build(tmp, Dialect.ANTHROPIC_PASSTHROUGH)
        val ctx = base.copy(
            head = base.head.copy(
                pinnedModel = shown.id,
                models = listOf(HeadModel(shown.id, slot = "opus")),
            ),
            providerCfg = base.providerCfg.copy(models = listOf(hidden, shown)),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-splice--",
                models = listOf(shown),
                defaultContextWindow = shown.contextWindow,
                pinnedModel = shown.id,
            ),
        )

        val spec = factory(tmp).launchSpecFor(ctx, 3099, forwardClientAuth = true)

        assertEquals(listOf(shown.id), spec.availableModelIds)
        assertEquals(mapOf(shown.id to shown.label), spec.modelLabels)
        assertEquals(mapOf(shown.id to "opus"), spec.tiers.slots)
        assertEquals(
            listOf(shown.id),
            spec.modelOptionsCache.jsonArray.map { it.jsonObject.getValue("value").jsonPrimitive.content },
        )
    }

    // V4-115: cross-head `-r SESSION_ID` resolves across the OTHER heads' config dirs — and nobody
    // else's. The list comes from the TOPOLOGY, never from a listing of $HOME: a directory that
    // merely exists on this machine is not a head this daemon serves, and reading someone's
    // transcripts from it would be exactly the leak V4-115 removes. The omitted-config_dir arm also
    // pins the per-head default: `~/.claude-<key>`, never the vanilla ~/.claude.
    @Test
    fun `sibling config dirs are the other topology heads, each under its own per-head default`(@TempDir tmp: Path) {
        val topology = Topology(
            heads = mapOf(
                "claude-kimi" to HeadConfig(
                    provider = "anthropic",
                    port = 3101,
                    discoveryPrefix = "claude-kimi--",
                    pinnedModel = "k3-256k",
                    claude = ClaudeWrapperConfig(command = "claude-kimi", configDir = "$tmp/kimi"),
                ),
                "claude-grok" to HeadConfig(
                    provider = "anthropic",
                    port = 3102,
                    discoveryPrefix = "claude-grok--",
                    pinnedModel = "grok-4",
                    claude = ClaudeWrapperConfig(command = "claude-grok"),
                ),
            ),
        )

        val spec = factory(tmp, topology).launchSpecFor(build(tmp, Dialect.ANTHROPIC_PASSTHROUGH), 3099, false)

        assertEquals(
            listOf(
                tmp.resolve("kimi").toString(),
                Paths.get(System.getProperty("user.home"), ".claude-claude-grok").toString(),
            ).sorted(),
            spec.trees.siblings.map { it.toString() }.sorted(),
        )
        assertFalse(spec.trees.siblings.contains(spec.trees.own), "a head never adopts from itself")
        assertFalse(
            spec.trees.siblings.any { it.fileName.toString() == ".claude" },
            "the vanilla ~/.claude tree is never a resume source",
        )
    }

    // V4-119: /statusline/{head} is now guarded(call) (ControlServer.kt:158-159, mgmt-key bearer),
    // but the command this factory writes into every head's settings used to carry no Authorization
    // header — so every launched head's status line 401d while the suite stayed green (the suite
    // tests the route, not the launch-time command string). This pin ties the command's bearer to the
    // factory's ACTUAL mgmt key (spec.inferenceToken), never a re-guessed token.
    @Test
    fun `the statusline command carries the mgmt bearer the guarded route accepts`(@TempDir tmp: Path) {
        val spec = factory(tmp).launchSpecFor(
            build(tmp, Dialect.ANTHROPIC_PASSTHROUGH),
            controlPort = 3099,
            forwardClientAuth = false,
        )
        assertTrue(
            spec.statuslineCommand.contains("Authorization: Bearer ${spec.inferenceToken}"),
            "the statusline command must carry the mgmt-key bearer — /statusline/{head} is guarded",
        )
    }

    // The pin that matters: drive the MATERIALIZED command against a real ControlServer statusline
    // route and assert the guard passes (200), not 401. The route is registered unconditionally
    // (ControlServer.kt:158) and `guarded` runs BEFORE any head resolution, so an empty heads map
    // still exercises the guard: the bearer clears it to a 200, its absence is a 401 — the exact
    // observable the defect was about. The command is driven as production drives it, via bash.
    @Test
    fun `the materialized statusline command clears the guarded route with a 200`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp)
        val port = freshPort()
        val server = ControlServer(
            port = port,
            heads = emptyMap(),
            config = ConfigService(paths),
            mgmtKey = MgmtKey(paths),
            dashboardHtml = { "" },
            log = {},
        )
        server.start()
        try {
            awaitListening(port)
            val spec = factory(tmp).launchSpecFor(
                build(tmp, Dialect.ANTHROPIC_PASSTHROUGH),
                controlPort = port,
                forwardClientAuth = false,
            )
            assertEquals(
                200,
                statuslineStatus(spec.statuslineCommand),
                "materialized command: ${spec.statuslineCommand}",
            )
        } finally {
            server.stop()
        }
    }

    /** The statusline command is a shell one-liner (`curl -sS … --data-binary @- …`) with no status
     *  code of its own: `-sS` prints the body and exits 0 on a 401 too. Append curl's
     *  `--write-out '%{http_code}'` (body redirected to /dev/null) so the SAME invocation — same
     *  URL, method, bearer, stdin source — reports the code we assert on. `{}` is the minimal
     *  statusline payload; the route answers before reading it when the head is unknown. */
    private fun statuslineStatus(command: String): Int {
        val driven = "$command --output /dev/null --write-out '%{http_code}'"
        val process = ProcessBuilder("bash", "-c", driven).start()
        process.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "statusline command did not exit within 10s: $driven" }
        return out.toInt()
    }

    private fun freshPort(): Int = ServerSocket(0).use { it.localPort }

    // Poll the CONDITION (a listening socket) with a deadline, never a sleep-for-a-duration:
    // Netty binds asynchronously after ControlServer.start(), and a fixed wait is the flaky guess
    // kt-tests-no-wall-clock forbids. LockSupport.parkNanos is the backoff, not Thread.sleep.
    private fun awaitListening(port: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (runCatching { Socket("127.0.0.1", port).use { } }.isFailure) {
            check(System.currentTimeMillis() < deadline) { "nothing listening on :$port" }
            LockSupport.parkNanos(5_000_000L)
        }
    }
}
