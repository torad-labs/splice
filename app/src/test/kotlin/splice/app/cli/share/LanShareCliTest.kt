// LAN share (docs/specs/lan-share.md, Marcos's ruling of 2026-10-24), its two verbs. The goal: on the server,
// `splice share on` turns sharing on and `splice share status` prints, for each command it offers, the address another
// machine uses; on a node, `splice remote add <address>` checks that address against the server, keeps it as one line
// in splice.toml and links the command, `add <server>` adds every command it offers, `splice remote remove` takes one
// out, and `splice remote` lists each with whether its server answers. Nothing the node keeps is a credential. Driven
// as it is used: a real sharing daemon (torad, its ChatGPT upstream mocked) and each verb run with a node's own
// splice.toml, bin folder and launch shim in a temp folder, whose daemon is down, so a verb that would restart it says
// it follows the file at its next start instead.
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
import splice.core.auth.RefreshAttempt
import splice.core.config.StatePaths
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
            log = { line -> if (line.startsWith("[daemon] control plane bound")) shareAtBound = accepts(sharePort) },
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        runBlocking { torad.start() }
        awaitListening(toradControl, toradClaudex, toradMini, sharePort)
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
    fun `share status prints each offered command at an address another machine adds it by`(@TempDir dir: Path) {
        val status = share(toradEnv, "status")
        assertEquals(0, status.code, status.toString())
        val claudex = address(status.out, "claudex")
        val mini = address(status.out, "claude-mini")
        val host = URI.create(claudex).host
        assertTrue(ownLanAddress(host), "$host is not one of this machine's LAN addresses\n$status")
        assertEquals(host, URI.create(mini).host, status.toString())

        val node = node(dir)
        val added = remote(node, "add", claudex)
        assertEquals(0, added.code, added.toString())
        assertEquals(mapOf("claudex" to RemoteConfig(claudex)), node.remote)
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
    }

    @Test
    fun `remote add keeps the address as one line and links the command, holding nothing else`(@TempDir dir: Path) {
        val node = node(dir)
        val before = TopologyLoader.parse(node.config.readText())
        val added = remote(node, "add", "$share/claudex?auth=work")
        assertEquals(0, added.code, added.toString())
        val after = TopologyLoader.parse(node.config.readText())
        assertEquals(mapOf("claudex" to RemoteConfig("$share/claudex?auth=work")), after.remote)
        assertEquals(before.copy(remote = after.remote), after, "nothing but the address is written")
        assertTrue(node.linked("claudex"), added.toString())
        assertFalse(Files.exists(dir.resolve("keys.toml")), "a node keeps no key")
        assertTrue(added.out.contains("claudex"), added.toString())
    }

    @Test
    fun `remote add whose command name is another program's file says so, and does not report it added`(
        @TempDir dir: Path,
    ) {
        val node = node(dir)
        Files.createDirectories(node.link("claudex").parent)
        Files.writeString(node.link("claudex"), "#!/bin/sh\necho the person's own claudex\n")
        val added = remote(node, "add", "$share/claudex")
        assertNotEquals(0, added.code, added.toString())
        assertTrue(added.err.contains(node.link("claudex").toString()), added.toString())
        assertFalse(node.linked("claudex"), added.toString())
    }

    @Test
    fun `a node the first build left is told at boot what replaces its relay, and remote add replaces it`(
        @TempDir dir: Path,
    ) {
        val node = firstBuildNode(dir)
        val booted = TopologyFindings.of(TopologyLoader.parse(node.config.readText()), stateBase = dir.resolve("state"))
        assertTrue(
            booted.any { it.path == "heads.claudex" && it.message.contains("splice remote add") },
            booted.joinToString("\n") { it.text() },
        )
        val added = remote(node, "add", "$share/claudex")
        assertEquals(0, added.code, added.toString())
        val after = TopologyLoader.parse(node.config.readText())
        assertEquals(mapOf("claudex" to RemoteConfig("$share/claudex")), after.remote)
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
    fun `remote add with the server alone adds every command it offers`(@TempDir dir: Path) {
        val node = node(dir)
        val added = remote(node, "add", share)
        assertEquals(0, added.code, added.toString())
        assertEquals(
            mapOf("claudex" to RemoteConfig("$share/claudex"), "claude-mini" to RemoteConfig("$share/claude-mini")),
            node.remote,
        )
        assertTrue(node.linked("claudex") && node.linked("claude-mini"), added.toString())
    }

    @Test
    fun `remote add naming a login or a model the server lacks is refused with what it has`(@TempDir dir: Path) {
        val node = node(dir)
        val before = node.config.readText()
        val login = remote(node, "add", "$share/claudex?auth=nobody")
        assertNotEquals(0, login.code, login.toString())
        assertTrue(login.err.contains("nobody") && login.err.contains("work"), login.toString())
        val model = remote(node, "add", "$share/claudex?models=gpt-0")
        assertNotEquals(0, model.code, model.toString())
        assertTrue(model.err.contains("gpt-0") && model.err.contains(MINI), model.toString())
        assertEquals(before, node.config.readText(), "a refused address writes nothing")
        assertFalse(Files.exists(node.link("claudex"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `remote add of a command the server does not offer names the ones it does`(@TempDir dir: Path) {
        val node = node(dir)
        val refused = remote(node, "add", "$share/grok")
        assertNotEquals(0, refused.code, refused.toString())
        assertTrue(refused.err.contains("grok") && refused.err.contains("claudex"), refused.toString())
        assertTrue(node.remote.isEmpty())
    }

    @Test
    fun `remote add to a server that can't be reached says so and writes nothing`(@TempDir dir: Path) {
        val node = node(dir)
        val before = node.config.readText()
        val refused = remote(node, "add", "http://127.0.0.1:$closedPort/claudex")
        assertNotEquals(0, refused.code, refused.toString())
        assertTrue(refused.err.contains("can't be reached"), refused.toString())
        assertEquals(before, node.config.readText())
    }

    @Test
    fun `a second address for a command added already takes another name with --as`(@TempDir dir: Path) {
        val node = node(dir)
        assertEquals(0, remote(node, "add", "$share/claudex").code)
        val again = remote(node, "add", "$share/claudex?auth=work")
        assertNotEquals(0, again.code, again.toString())
        assertTrue(again.err.contains("--as"), again.toString())
        val named = remote(node, "add", "$share/claudex?auth=work", "--as", "claudex-work")
        assertEquals(0, named.code, named.toString())
        val both = mapOf(
            "claudex" to RemoteConfig("$share/claudex"),
            "claudex-work" to RemoteConfig("$share/claudex?auth=work"),
        )
        assertEquals(both, node.remote)
        assertTrue(node.linked("claudex-work"), named.toString())
    }

    @Test
    fun `remote add --as claude keeps the address and leaves the claude link to Claude Code and the wrap`(
        @TempDir dir: Path,
    ) {
        val node = node(dir)
        // `claude` is Claude Code's own link, which splice's wrap points at the launch shim: never a remote's to make.
        val own = Files.writeString(Files.createDirectories(dir.resolve("claude-versions")).resolve("2.1.310"), "")
        Files.createDirectories(node.link("claude").parent)
        Files.createSymbolicLink(node.link("claude"), own)
        val added = remote(node, "add", "$share/claudex", "--as", "claude")
        assertEquals(0, added.code, added.toString())
        assertEquals(mapOf("claude" to RemoteConfig("$share/claudex")), node.remote)
        assertEquals(own, Files.readSymbolicLink(node.link("claude")), "adding it left the claude link alone")
        val lines = TerminalOutput {}
        InstallCommand(lines, lines).install(null, node.env)
        assertEquals(own, Files.readSymbolicLink(node.link("claude")), "splice install left the claude link alone")
        InstallCommand(lines, lines).uninstall(null, node.env)
        assertEquals(own, Files.readSymbolicLink(node.link("claude")), "splice uninstall left the claude link alone")
        val removed = remote(node, "remove", "claude")
        assertEquals(0, removed.code, removed.toString())
        assertTrue(node.remote.isEmpty(), node.config.readText())
        assertEquals(own, Files.readSymbolicLink(node.link("claude")), "removing it left the claude link alone")
    }

    @Test
    fun `remote remove takes the command and its link out`(@TempDir dir: Path) {
        val node = node(dir)
        assertEquals(0, remote(node, "add", "$share/claudex").code)
        val removed = remote(node, "remove", "claudex")
        assertEquals(0, removed.code, removed.toString())
        assertTrue(node.remote.isEmpty(), node.config.readText())
        assertFalse(Files.exists(node.link("claudex"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `remote lists each command with whether its server answers`(@TempDir dir: Path) {
        val node = node(dir)
        Files.writeString(
            node.config,
            node.config.readText() + "\n[remote.claudex]\nurl = \"$share/claudex\"\n\n" +
                "[remote.offline]\nurl = \"http://127.0.0.1:$closedPort/claudex\"\n",
        )
        val listed = remote(node)
        assertEquals(0, listed.code, listed.toString())
        val lines = listed.out.lines()
        val claudex = lines.single { it.trimStart().startsWith("claudex ") }
        val offline = lines.single { it.trimStart().startsWith("offline ") }
        assertTrue(claudex.contains("$share/claudex") && !claudex.contains("can't be reached"), listed.toString())
        assertTrue(offline.contains("can't be reached"), listed.toString())
    }

    @Test
    fun `splice install links the remote commands too`(@TempDir dir: Path) {
        val node = node(dir)
        Files.writeString(node.config, node.config.readText() + "\n[remote.claudex]\nurl = \"$share/claudex\"\n")
        val lines = TerminalOutput {}
        InstallCommand(lines, lines).install(null, node.env)
        assertTrue(node.linked("claudex"))
    }

    /** The address [status] printed for [command]: `http://<host>:<share port>/<command>`. */
    private fun address(status: String, command: String): String {
        val printed = Regex("""http://\S+:$sharePort/${Regex.escape(command)}(?=\s|$)""")
        return checkNotNull(printed.findAll(status).firstOrNull()?.value) { "no address for $command in\n$status" }
    }

    /** Whether [host] names one of this machine's own addresses, other than loopback. */
    private fun ownLanAddress(host: String): Boolean {
        val own = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.toSet()
        return InetAddress.getAllByName(host).any { it in own && !it.isLoopbackAddress }
    }

    private fun accepts(port: Int): Boolean = runCatching { Socket("127.0.0.1", port).use { } }.isSuccess

    private fun awaitListening(vararg ports: Int) {
        val deadline = System.currentTimeMillis() + 15_000
        for (port in ports) {
            while (runCatching { Socket("127.0.0.1", port).use { } }.isFailure) {
                check(System.currentTimeMillis() < deadline) { "nothing listening on :$port" }
                LockSupport.parkNanos(5_000_000L)
            }
        }
    }
}

private fun Path.esc() = toString().replace("\\", "/")
