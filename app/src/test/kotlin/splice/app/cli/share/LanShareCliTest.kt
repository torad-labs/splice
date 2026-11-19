// The door on the network (docs/specs/door-network.md), its two verbs. The goal: on the box, `splice share on` turns
// sharing on and `splice share status` prints the door another machine's `claude` runs on, the one
// `splice remote add http://<box>` to run there and the variables a Claude Code without splice uses, and nothing per
// command (share-door-only, Nov 19, 2026: a machine shares its door alone). On a node, `splice remote add <box>` checks
// the box's door, keeps it as one line in splice.toml and links the command, an address naming one command of a box
// this node holds no key for is refused with the add for its door, `splice remote remove` takes one out, and
// `splice remote` lists each with whether its door answers. Nothing the node keeps is a credential. Driven as it is
// used: a real sharing daemon (torad, its ChatGPT upstream mocked) and each verb run with a node's own splice.toml, bin
// folder and launch shim in a temp folder, whose daemon is down, so a verb that would restart it says it follows the
// file at its next start instead. No test here drives a per-command route of the box.
package splice.app.cli.share

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.client.wrap.WrapResult
import splice.client.wrap.WrapStateStore
import splice.client.wrap.WrappedHead
import splice.core.auth.RefreshAttempt
import splice.core.config.InstallPaths
import splice.core.config.StatePaths
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.testing.TestPorts
import splice.core.topology.AuthKind
import splice.core.topology.RemoteConfig
import splice.core.topology.TopologyFindings
import splice.core.util.EnvReader
import splice.head.MockChatGptUpstream
import splice.launch.install.InstallCommand
import splice.oauth.OAuthAccountFiles
import splice.topology.TopologyLoader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.LockSupport
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText

private const val SOL = "gpt-5.6-sol"
private const val MINI = "gpt-5.6-mini"

// why: the door opens once the box's heads are up; a loaded machine takes a while to start them.
private const val DOOR_OPEN_MS = 60_000L

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LanShareCliTest {
    private val upstream = MockChatGptUpstream()
    private val toradControl = TestPorts.reserve()
    private val toradClaudex = TestPorts.reserve()
    private val toradMini = TestPorts.reserve()
    private val sharePort = TestPorts.reserve()
    private val closedPort = TestPorts.reserve()
    private lateinit var torad: Daemon
    private lateinit var toradEnv: EnvReader
    private lateinit var toradState: Path

    /** Whether torad's share port accepted at the moment its control port was bound: from then `/health` answers, and
     *  `splice share on`, which restarts and waits for `/health`, reads the share port at once. */
    @Volatile private var shareAtBound: Boolean? = null
    private val share get() = "http://127.0.0.1:$sharePort"

    @BeforeAll
    fun setUp(@TempDir tmp: Path) {
        val primary = tmp.resolve("torad-codex.json")
        Files.writeString(primary, """{"tokens":{"access_token":"synthetic-primary","account_id":"primary-id"}}""")
        OAuthAccountFiles().writeLabeled(
            AuthKind.ChatgptOAuth,
            primary,
            "work",
            Json.parseToJsonElement("""{"tokens":{"access_token":"synthetic-work","account_id":"work-id"}}""")
                .jsonObject,
        )
        val config = tmp.resolve("torad/splice.toml")
        Files.createDirectories(config.parent)
        Files.writeString(config, toradToml(primary))
        toradEnv = envOf(tmp.resolve("torad"))
        // What the first build's server kept of the machines that joined it, there when this build starts.
        toradState = tmp.resolve("torad-state")
        Files.createDirectories(toradState.resolve("share"))
        Files.writeString(toradState.resolve("share/clients.json"), """{"clients":[{"name":"mac","sha256":"00"}]}""")
        Files.writeString(toradState.resolve("share/clients.json.lock"), "")
        torad = Daemon(
            topology = TopologyLoader.parse(config.readText()),
            statePaths = StatePaths(baseOverride = toradState),
            log = { line ->
                if (line.startsWith("[daemon] control plane bound")) shareAtBound = Probe.accepts(sharePort)
            },
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        runBlocking { torad.start() }
        Probe.awaitListening(toradControl, toradClaudex, toradMini, sharePort)
        Probe.awaitDoor(share)
    }

    @AfterAll
    fun tearDown() {
        runBlocking { torad.stop() }
        upstream.stop()
    }

    private fun toradToml(primary: Path): String = """
        [daemon]
        control_port = $toradControl

        [share]
        enabled = true
        port = $sharePort

        [providers.codex]
        dialect = "openai-responses"
        base_url = "${upstream.baseUrl}"
        auth = { kind = "chatgpt-oauth", file = "${primary.esc()}" }
        quirks = { account_id_header = true, cache_key = "first-message-hash", summary_field = true }
        [[providers.codex.models]]
        id = "$SOL"
        context_window = 272000
        [[providers.codex.models]]
        id = "$MINI"
        context_window = 128000

        [heads.claudex]
        provider = "codex"
        port = $toradClaudex
        discovery_prefix = "claude-codex--"
        pinned_model = "$SOL"

        [heads.mini]
        provider = "codex"
        port = $toradMini
        discovery_prefix = "claude-mini--"
        pinned_model = "$MINI"
        [heads.mini.claude]
        command = "claude-mini"
    """.trimIndent()

    /** A node: its own splice.toml (a daemon on a port nothing answers, and one head of its own), bin folder and
     *  launch shim, all under [dir]. */
    private fun node(dir: Path): Node {
        Files.createDirectories(dir.resolve("share"))
        Files.writeString(dir.resolve("share/splice-launch"), "#!/bin/sh\n")
        Files.writeString(
            dir.resolve("splice.toml"),
            """
            [daemon]
            control_port = $closedPort

            [providers.anthropic]
            dialect = "anthropic-passthrough"
            base_url = "https://api.anthropic.com"
            auth = { kind = "client" }

            [heads.plain]
            provider = "anthropic"
            port = ${TestPorts.reserve()}
            discovery_prefix = "claude-plain--"
            """.trimIndent() + "\n",
        )
        return Node(dir, envOf(dir))
    }

    /** A node as the first LAN-share build left it: `claudex` a head of its own, relaying through a provider it wrote
     *  to the server's old route, with that build's share token in keys.toml. */
    private fun firstBuildNode(dir: Path): Node {
        val node = node(dir)
        val relay = """
            [providers.claudex]
            dialect = "anthropic-passthrough"
            base_url = "$share/share/v1/heads/claudex"
            auth = { kind = "api-key", env = "CLAUDEX_API_KEY" }
            quirks = { forward_client_identity = true }
            [[providers.claudex.models]]
            id = "$SOL"
            context_window = 272000

            [heads.claudex]
            provider = "claudex"
            port = ${TestPorts.reserve()}
            discovery_prefix = "claude-claudex--"
            pinned_model = "$SOL"
            [heads.claudex.claude]
            command = "claudex"
        """.trimIndent()
        Files.writeString(node.config, node.config.readText() + "\n" + relay + "\n")
        Files.writeString(dir.resolve("keys.toml"), "CLAUDEX_API_KEY = \"first-build-share-token\"\n")
        return node
    }

    private fun share(env: EnvReader, vararg args: String): Run = captured { out, err ->
        runBlocking { ShareCommand(out, err, env).run(args.toList()) }
    }

    private fun remote(node: Node, vararg args: String): Run = captured { out, err ->
        runBlocking { RemoteCommand(out, err, node.env).run(args.toList()) }
    }

    private fun captured(run: (TerminalOutput, TerminalOutput) -> Int): Run {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = run(TerminalOutput { out.appendLine(it) }, TerminalOutput { err.appendLine(it) })
        return Run(code, out.toString(), err.toString())
    }

    @Test
    fun `share status prints the door, the add for it and the variables, and no command's own address`(
        @TempDir dir: Path,
    ) {
        val status = share(toradEnv, "status")
        assertEquals(0, status.code, status.toString())
        val box = server(status.out)
        val host = URI.create(box).host
        assertTrue(Probe.ownLanAddress(host), "$host is not one of this machine's LAN addresses\n$status")
        assertTrue("ANTHROPIC_BEDROCK_MANTLE_BASE_URL=$box/door" in status.out, status.toString())
        assertTrue("AWS_BEARER_TOKEN_BEDROCK=splice-lan" in status.out, status.toString())
        assertTrue(Regex("""\d+ models?|no account signed in yet""").containsMatchIn(status.out), status.toString())
        for (gone in listOf("$box/claudex", "$box/claude-mini", "?auth=", "?models=", "deprecated")) {
            assertFalse(gone in status.toString(), "status still prints $gone\n$status")
        }

        val node = node(dir)
        val added = remote(node, "add", box, "--as", "torad")
        assertEquals(0, added.code, added.toString())
        assertEquals(mapOf("torad" to RemoteConfig("$box/door")), node.remote)
    }

    @Test
    fun `the share port answers by the time the control port does, so share on reads its address after a restart`() {
        assertEquals(true, shareAtBound, "the share listener was not yet open when /health first answered")
    }

    @Test
    fun `share on a port this machine can't open says why in the OS's words, sharing off`(@TempDir dir: Path) {
        val node = node(dir)
        ServerSocket(0).use { holder ->
            val port = holder.localPort
            val on = share(node.env, "on", "--port", "$port")
            assertNotEquals(0, on.code, on.toString())
            assertTrue(on.err.contains("port $port can't be opened here (Address already in use)"), on.toString())
            assertFalse(on.toString().contains("is splice running"), on.toString())
            assertFalse(TopologyLoader.parse(node.config.readText()).share.enabled, node.config.readText())
        }
    }

    @Test
    fun `share on a port outside 1-65535 is refused in the config's own words, sharing off`(@TempDir dir: Path) {
        val node = node(dir)
        for (port in listOf("70000", "-1")) {
            val on = share(node.env, "on", "--port", port)
            assertNotEquals(0, on.code, on.toString())
            assertTrue(on.err.contains("port $port is outside 1-65535"), on.toString())
            assertFalse(TopologyLoader.parse(node.config.readText()).share.enabled, node.config.readText())
        }
    }

    @Test
    fun `share on turns sharing on in splice toml and keeps the rest of it, and off turns it off`(@TempDir dir: Path) {
        val node = node(dir)
        val port = TestPorts.reserve()
        val before = TopologyLoader.parse(node.config.readText())
        val on = share(node.env, "on", "--port", "$port")
        assertEquals(0, on.code, on.toString())
        val shared = TopologyLoader.parse(node.config.readText())
        assertTrue(shared.share.enabled, node.config.readText())
        assertEquals(port, shared.share.port)
        assertEquals(before.heads, shared.heads)
        assertEquals(before.providers, shared.providers)
        assertTrue(on.out.contains("not running"), "a daemon that is down follows the file at its next start\n$on")

        val off = share(node.env, "off")
        assertEquals(0, off.code, off.toString())
        assertFalse(TopologyLoader.parse(node.config.readText()).share.enabled, node.config.readText())
        assertTrue("door" in share(node.env, "status").out, "sharing off says what turning it on shares")
    }

    @Test
    fun `remote add keeps the door as one line and links the command, holding nothing else`(@TempDir dir: Path) {
        val node = node(dir)
        val before = TopologyLoader.parse(node.config.readText())
        val added = remote(node, "add", share, "--as", "torad")
        assertEquals(0, added.code, added.toString())
        val after = TopologyLoader.parse(node.config.readText())
        assertEquals(mapOf("torad" to RemoteConfig("$share/door")), after.remote)
        assertEquals(before.copy(remote = after.remote), after, "nothing but the address is written")
        assertTrue(node.linked("torad"), added.toString())
        assertFalse(Files.exists(dir.resolve("keys.toml")), "a node keeps no key")
        assertTrue(added.out.contains("torad"), added.toString())
    }

    @Test
    fun `remote add whose command name is another program's file says so, and does not report it added`(
        @TempDir dir: Path,
    ) {
        val node = node(dir)
        Files.createDirectories(node.link("torad").parent)
        Files.writeString(node.link("torad"), "#!/bin/sh\necho the person's own torad\n")
        val added = remote(node, "add", share, "--as", "torad")
        assertNotEquals(0, added.code, added.toString())
        assertTrue(added.err.contains(node.link("torad").toString()), added.toString())
        assertFalse(node.linked("torad"), added.toString())
    }

    @Test
    fun `a node the first build left is told at boot what replaces its relay, and remote add --as replaces it`(
        @TempDir dir: Path,
    ) {
        val node = firstBuildNode(dir)
        val booted = TopologyFindings.of(TopologyLoader.parse(node.config.readText()), stateBase = dir.resolve("state"))
        val said = booted.singleOrNull { it.path == "heads.claudex" }?.message.orEmpty()
        assertTrue("splice remote add" in said && "--as claudex" in said, booted.joinToString("\n") { it.text() })
        val added = remote(node, "add", share, "--as", "claudex")
        assertEquals(0, added.code, added.toString())
        val after = TopologyLoader.parse(node.config.readText())
        assertEquals(mapOf("claudex" to RemoteConfig("$share/door")), after.remote)
        assertFalse("claudex" in after.heads || "claudex" in after.providers, node.config.readText())
        assertFalse(dir.resolve("keys.toml").readText().contains("CLAUDEX_API_KEY"), "the share token is still kept")
        assertTrue(node.linked("claudex"), added.toString())
    }

    @Test
    fun `a server the first build ran keeps no record of the machines that joined it`() {
        assertFalse(Files.exists(toradState.resolve("share/clients.json")), "clients.json is still there")
        assertFalse(Files.exists(toradState.resolve("share/clients.json.lock")), "its lock is still there")
    }

    @Test
    fun `remote add with the box alone puts a wrapped claude on the box's door`(@TempDir dir: Path) {
        val node = node(dir)
        node.wrapped()
        // A door serves every model of every account, so no login or model can be asked of it.
        val asked = remote(node, "add", "$share/door?models=gpt-0")
        assertNotEquals(0, asked.code, asked.toString())
        assertTrue("every model of every account" in asked.err, asked.toString())
        val added = remote(node, "add", share)
        assertEquals(0, added.code, added.toString())
        assertEquals(mapOf("claude" to RemoteConfig("$share/door")), node.remote)
        assertFalse(node.linked("claudex"), "the door has every command's models: none is linked on its own")
    }

    // share-door-only: these replace the adds of one command that checked a login, a model or the command against
    // what the box offered. A box this node holds no key for shares its door alone, whatever the address asks for.
    @Test
    fun `remote add of one command's address on a keyless box is refused with the add for its door`(
        @TempDir dir: Path,
    ) {
        val node = node(dir)
        val before = node.config.readText()
        val addresses = listOf(
            "$share/claudex",
            "$share/claudex?auth=work",
            "$share/claude-mini?models=$MINI",
            "$share/mini",
            "$share/grok",
        )
        for (address in addresses) {
            val refused = remote(node, "add", address)
            assertNotEquals(0, refused.code, "$address\n$refused")
            assertTrue("shares its door" in refused.err, "$address\n$refused")
            assertTrue(refused.err.trimEnd().endsWith("splice remote add $share"), "$address\n$refused")
        }
        val named = remote(node, "add", "$share/claudex", "--as", "work")
        assertTrue(named.err.trimEnd().endsWith("splice remote add $share --as work"), named.toString())
        assertEquals(before, node.config.readText(), "a refused address writes nothing")
        for (command in listOf("claudex", "claude-mini", "mini", "work")) {
            assertFalse(Files.exists(node.link(command), java.nio.file.LinkOption.NOFOLLOW_LINKS), command)
        }
    }

    @Test
    fun `remote add of a box that can't be reached says so and writes nothing`(@TempDir dir: Path) {
        val node = node(dir)
        val before = node.config.readText()
        for (address in listOf("http://127.0.0.1:$closedPort", "http://127.0.0.1:$closedPort/claudex")) {
            val refused = remote(node, "add", address, "--as", "gone")
            assertNotEquals(0, refused.code, "$address\n$refused")
            assertTrue(refused.err.contains("can't be reached"), "$address\n$refused")
        }
        assertEquals(before, node.config.readText())
    }

    @Test
    fun `a name a command here already has takes another with --as`(@TempDir dir: Path) {
        val node = node(dir)
        val taken = remote(node, "add", share, "--as", "plain")
        assertNotEquals(0, taken.code, taken.toString())
        assertTrue(taken.err.contains("--as"), taken.toString())
        assertTrue(node.remote.isEmpty(), node.config.readText())
    }

    @Test
    fun `remote add --as claude on a wrapped node runs plain claude there and leaves the link to the wrap`(
        @TempDir dir: Path,
    ) {
        val node = node(dir)
        val shim = node.wrapped()
        val added = remote(node, "add", share, "--as", "claude")
        assertEquals(0, added.code, added.toString())
        assertTrue("claude$RESET runs on " in added.out, added.toString())
        assertEquals(mapOf("claude" to RemoteConfig("$share/door")), node.remote)
        // `claude` is Claude Code's own link, which the wrap points at the launch shim: never a remote's to make.
        assertEquals(shim, Files.readSymbolicLink(node.link("claude")), "adding it left the wrap's link alone")
        val lines = TerminalOutput {}
        InstallCommand(lines, lines).install(null, node.env)
        assertEquals(shim, Files.readSymbolicLink(node.link("claude")), "splice install left the wrap's link alone")
        val removed = remote(node, "remove", "claude")
        assertEquals(0, removed.code, removed.toString())
        assertTrue(node.remote.isEmpty(), node.config.readText())
        assertEquals(shim, Files.readSymbolicLink(node.link("claude")), "removing it left the wrap's link alone")
    }

    // PR 423 review: on a node whose `claude` is not wrapped, plain `claude` is Claude Code itself and never asks
    // splice where to run, so a `[remote.claude]` would change nothing while the add said it did.
    @Test
    fun `remote add --as claude on a node whose claude is not wrapped is refused, naming how to wrap it`(
        @TempDir dir: Path,
    ) {
        val node = node(dir)
        val own = node.claudeCodeOwn()
        val added = remote(node, "add", share, "--as", "claude")
        assertNotEquals(0, added.code, added.toString())
        assertFalse("runs on" in added.out, "nothing runs on torad: $added")
        assertTrue("splice setup" in added.err, "the refusal names how to wrap claude: $added")
        assertTrue(node.remote.isEmpty(), node.config.readText())
        assertEquals(own, Files.readSymbolicLink(node.link("claude")), "the refused add left the claude link alone")
        // A `[remote.claude]` written by hand boots (LanShareConfigTest); install and uninstall still leave Claude
        // Code's own link alone.
        Files.writeString(node.config, node.config.readText() + "\n[remote.claude]\nurl = \"$share/door\"\n")
        val lines = TerminalOutput {}
        InstallCommand(lines, lines).install(null, node.env)
        assertEquals(own, Files.readSymbolicLink(node.link("claude")), "splice install left the claude link alone")
        InstallCommand(lines, lines).uninstall(null, node.env)
        assertEquals(own, Files.readSymbolicLink(node.link("claude")), "splice uninstall left the claude link alone")
    }

    @Test
    fun `remote remove takes the command and its link out`(@TempDir dir: Path) {
        val node = node(dir)
        assertEquals(0, remote(node, "add", share, "--as", "torad").code)
        val removed = remote(node, "remove", "torad")
        assertEquals(0, removed.code, removed.toString())
        assertTrue(node.remote.isEmpty(), node.config.readText())
        assertFalse(Files.exists(node.link("torad"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `remote lists each door with whether it answers and how many models it serves`(@TempDir dir: Path) {
        val node = node(dir)
        val empty = remote(node)
        assertTrue("splice remote add http://" in empty.out, empty.toString())
        Files.writeString(
            node.config,
            node.config.readText() + "\n[remote.torad]\nurl = \"$share/door\"\n\n" +
                "[remote.offline]\nurl = \"http://127.0.0.1:$closedPort/door\"\n",
        )
        val listed = remote(node)
        assertEquals(0, listed.code, listed.toString())
        val lines = listed.out.lines()
        val torad = lines.single { it.trimStart().startsWith("torad ") }
        val offline = lines.single { it.trimStart().startsWith("offline ") }
        val ready = Regex("""ready on \S+'s door, (\d+ models?|no account signed in yet)""")
        assertTrue(ready.containsMatchIn(torad), listed.toString())
        assertTrue(offline.contains("can't be reached"), listed.toString())
    }

    @Test
    fun `splice install links the remote commands too`(@TempDir dir: Path) {
        val node = node(dir)
        Files.writeString(node.config, node.config.readText() + "\n[remote.torad]\nurl = \"$share/door\"\n")
        val lines = TerminalOutput {}
        InstallCommand(lines, lines).install(null, node.env)
        assertTrue(node.linked("torad"))
    }

    /** The box [status] prints the add for: `http://<host>:<share port>`. */
    private fun server(status: String): String {
        val printed = Regex("""splice remote add (http://\S+:$sharePort)(?=\s|$)""")
        return checkNotNull(printed.find(status)?.groupValues?.get(1)) { "no add line in\n$status" }
    }
}

/** Waits on and reads the listeners a test drives, over loopback. */
private object Probe {
    /** Waits until torad's door has opened: it answers its launch once its heads are up. */
    fun awaitDoor(share: String) {
        val deadline = System.currentTimeMillis() + DOOR_OPEN_MS
        val launch = URI.create("$share/door/launch").toURL()
        while (status(launch) != HttpURLConnection.HTTP_OK) {
            check(System.currentTimeMillis() < deadline) { "torad's door did not open" }
            LockSupport.parkNanos(50_000_000L)
        }
    }

    /** What [url] answers a GET with, or null when nothing answers. */
    fun status(url: java.net.URL): Int? = runCatching {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** Whether [host] names one of this machine's own addresses, other than loopback. */
    fun ownLanAddress(host: String): Boolean {
        val own = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.toSet()
        return InetAddress.getAllByName(host).any { it in own && !it.isLoopbackAddress }
    }

    fun accepts(port: Int): Boolean = runCatching { Socket("127.0.0.1", port).use { } }.isSuccess

    fun awaitListening(vararg ports: Int) {
        val deadline = System.currentTimeMillis() + 15_000
        for (port in ports) {
            while (runCatching { Socket("127.0.0.1", port).use { } }.isFailure) {
                check(System.currentTimeMillis() < deadline) { "nothing listening on :$port" }
                LockSupport.parkNanos(5_000_000L)
            }
        }
    }
}

private fun envOf(dir: Path): EnvReader {
    val vars = mapOf(
        "SPLICE_CONFIG" to dir.resolve("splice.toml").toString(),
        "SPLICE_BIN_DIR" to dir.resolve("bin").toString(),
        "SPLICE_SHARE_DIR" to dir.resolve("share").toString(),
        "SPLICE_STATE_DIR" to dir.resolve("state").toString(),
        "HOME" to dir.toString(),
        "PATH" to dir.resolve("bin").toString(),
    )
    return EnvReader { vars[it] }
}

/** One machine's run of a verb: its exit code, and what it printed on stdout and stderr. */
private data class Run(val code: Int, val out: String, val err: String) {
    override fun toString(): String = "exit $code\n--- out\n$out\n--- err\n$err"
}

private class Node(val dir: Path, val env: EnvReader) {
    val config: Path get() = dir.resolve("splice.toml")
    val remote: Map<String, RemoteConfig> get() = TopologyLoader.parse(config.readText()).remote

    fun link(command: String): Path = dir.resolve("bin").resolve(command)

    /** Whether [command] is linked to this node's launch shim, as every splice command is. */
    fun linked(command: String): Boolean = link(command).isSymbolicLink() &&
        Files.readSymbolicLink(link(command)) == dir.resolve("share/splice-launch")

    /** This node's `claude` as Claude Code's installer leaves it: a link to the release it runs. */
    fun claudeCodeOwn(): Path {
        val own = Files.writeString(
            Files.createDirectories(dir.resolve("claude-versions")).resolve("2.1.310"),
            "#!/bin/sh\n",
        )
        own.toFile().setExecutable(true)
        Files.createDirectories(link("claude").parent)
        Files.createSymbolicLink(link("claude"), own)
        return own
    }

    /** This node's `claude` wrapped as `splice setup` wraps it: the launch shim in its place, the release it ran
     *  recorded in the node's own state folder. */
    fun wrapped(): Path {
        claudeCodeOwn()
        val store = WrapStateStore(StatePaths(envReader = env).stateDir.resolve("claude-head-wrap.json"))
        val wrap = WrappedHead(dir, InstallPaths(envReader = env), store).wrap()
        check(wrap is WrapResult.Ok) { "the node's wrap: $wrap" }
        return dir.resolve("share/splice-launch")
    }
}

private fun Path.esc() = toString().replace("\\", "/")
