// `splice server` as an operator runs it (docs/specs/server-mode.md, F1 and F3): against a real daemon whose
// splice.toml has a `[server]`, each verb is run through its control plane, and what it did is read back off the
// server's listener, where a key either opens the door or does not.
package splice.app.cli.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import splice.topology.TopologyLoader
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.LockSupport

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerCliTest {
    private val control = TestPorts.reserve()
    private val listen = TestPorts.reserve()
    private val plainControl = TestPorts.reserve()
    private val http = HttpClient.newHttpClient()
    private val keyShape = Regex("^spk_[a-z0-9]{8}_[A-Za-z0-9]{32,}$")
    private lateinit var server: Machine
    private lateinit var plain: Machine

    /** One machine's daemon, and the environment its `splice` runs in. */
    private data class Machine(val daemon: Daemon, val env: EnvReader)

    @BeforeAll
    fun setUp(@TempDir tmp: Path) {
        val team = """
            [daemon]
            control_port = $control

            [server]
            listen = "127.0.0.1:$listen"
            commands = ["claude-team"]

            [providers.box]
            dialect = "openai-chat"
            base_url = "http://127.0.0.1:${TestPorts.reserve()}/v1"
            auth = { kind = "api-key", env = "SPLICE_TEST_BOX_KEY" }

            [[providers.box.models]]
            id = "team-model"
            label = "team-model"
            context_window = 131072

            [heads.box]
            provider = "box"
            port = ${TestPorts.reserve()}
            discovery_prefix = "claude-box--"
            pinned_model = "team-model"
            [heads.box.claude]
            command = "claude-team"
        """.trimIndent() + "\n"
        server = boot(tmp.resolve("server"), team + farm())
        plain = boot(tmp.resolve("plain"), "[daemon]\ncontrol_port = $plainControl\n")
        awaitListening(control, listen, plainControl)
    }

    /** A provider that is two servers, behind a command of its own: the upstreams a person can be set to. */
    private fun farm(): String = """

        [providers.farm]
        dialect = "openai-chat"
        auth = { kind = "api-key", env = "SPLICE_TEST_BOX_KEY" }
        health_seconds = 60

        [[providers.farm.upstreams]]
        name = "gpu-1"
        url = "http://127.0.0.1:${TestPorts.reserve()}/v1"

        [[providers.farm.upstreams]]
        name = "gpu-2"
        url = "http://127.0.0.1:${TestPorts.reserve()}/v1"

        [[providers.farm.models]]
        id = "team-model"
        label = "team-model"
        context_window = 131072

        [heads.farm]
        provider = "farm"
        port = ${TestPorts.reserve()}
        discovery_prefix = "claude-farm--"
        pinned_model = "team-model"
        [heads.farm.claude]
        command = "claude-farm"
    """.trimIndent() + "\n"

    private fun boot(dir: Path, toml: String): Machine {
        val config = Files.createDirectories(dir).resolve("splice.toml")
        Files.writeString(config, toml)
        val state = dir.resolve("state")
        val vars = mapOf("SPLICE_CONFIG" to "$config", "SPLICE_STATE_DIR" to "$state", "HOME" to "$dir")
        val daemon = Daemon(
            topology = TopologyLoader.parse(toml),
            statePaths = StatePaths(baseOverride = state),
            log = {},
            topologyPath = config,
        )
        runBlocking { daemon.start() }
        return Machine(daemon, EnvReader { vars[it] })
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            server.daemon.stop()
            plain.daemon.stop()
        }
    }

    private fun awaitListening(vararg ports: Int) {
        val deadline = System.currentTimeMillis() + 15_000
        for (port in ports) {
            while (runCatching { Socket("127.0.0.1", port).use { } }.isFailure) {
                check(System.currentTimeMillis() < deadline) { "nothing listening on :$port" }
                LockSupport.parkNanos(5_000_000L)
            }
        }
    }

    private fun splice(machine: Machine, vararg args: String): Run {
        val out = StringBuilder()
        val err = StringBuilder()
        val printed = TerminalOutput { out.appendLine(it) }
        val refused = TerminalOutput { err.appendLine(it) }
        return Run(ServerCommand(printed, refused, machine.env).run(args.toList()), out.toString(), err.toString())
    }

    /** A key issued to [person], as `key create` prints it. */
    private fun keyFor(person: String): String {
        val made = splice(server, "key", "create", "--person", person)
        assertEquals(0, made.code, made.toString())
        return made.out.lines().first()
    }

    private fun prefixOf(key: String): String = key.split('_').take(2).joinToString("_")

    /** What the server's listener answers [key] asking what it offers: 200 for a live key, 401 for any other. */
    private fun door(key: String): Int {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$listen/")).header("x-api-key", key).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    private fun json(run: Run): JsonObject {
        assertEquals(0, run.code, run.toString())
        return Json.parseToJsonElement(run.out).jsonObject
    }

    private fun text(row: JsonObject, field: String): String = row.getValue(field).jsonPrimitive.content

    @Test
    fun `key create prints the key on a line of its own, and that key opens the server`() {
        val made = splice(server, "key", "create", "--person", "Ada@Team.example", "--name", "laptop")
        assertEquals(0, made.code, made.toString())
        val key = made.out.lines().first()
        assertTrue(keyShape.matches(key), made.out)
        assertTrue(listOf("ada@team.example", "laptop").all(made.out::contains), made.out)
        assertEquals(200, door(key))

        val listed = splice(server, "key", "list", "--person", "ada@team.example")
        assertEquals(0, listed.code, listed.toString())
        assertTrue(listOf(prefixOf(key), "ada@team.example", "laptop", "live").all(listed.out::contains), listed.out)
        assertFalse(listed.out.contains(key.substringAfterLast('_')), "the list prints the key")
    }

    @Test
    fun `--json prints the daemon's own answer, for a script to read`() {
        val made = json(splice(server, "key", "create", "--person", "bo@team.example", "--expires", "30d", "--json"))
        assertTrue(keyShape.matches(text(made, "key")), made.toString())
        assertEquals(prefixOf(text(made, "key")), text(made, "prefix"))
        assertEquals("bo@team.example", text(made, "person"))
        assertEquals("operator", text(made, "source"))
        assertTrue(made.getValue("expires_at") !is JsonNull, "--expires set no expiry")

        val listed = splice(server, "key", "list", "--person", "bo@team.example", "--json")
        val row = json(listed).getValue("keys").jsonArray.single().jsonObject
        assertEquals(text(made, "prefix"), text(row, "prefix"))
        assertFalse(listed.out.contains(text(made, "key").substringAfterLast('_')), "the list prints the key")
    }

    @Test
    fun `key revoke stops one key at its next request, and says whose it was`() {
        val key = keyFor("cy@team.example")
        assertEquals(200, door(key))
        val revoked = splice(server, "key", "revoke", prefixOf(key))
        assertEquals(0, revoked.code, revoked.toString())
        assertTrue(listOf(prefixOf(key), "cy@team.example").all(revoked.out::contains), revoked.out)
        assertEquals(401, door(key))

        val unknown = splice(server, "key", "revoke", "spk_nosuchkey")
        assertEquals(1, unknown.code, unknown.toString())
        assertTrue(unknown.err.contains("no key has the prefix"), unknown.err)
    }

    @Test
    fun `key revoke-person revokes each of a person's keys, prints how many, and leaves everyone else's`() {
        val theirs = List(2) { keyFor("olga@team.example") }
        val other = keyFor("dee@team.example")
        val gone = splice(server, "key", "revoke-person", "OLGA@team.example")
        assertEquals(0, gone.code, gone.toString())
        assertTrue(listOf("2 keys", "olga@team.example").all(gone.out::contains), gone.out)
        theirs.forEach { assertEquals(401, door(it)) }
        assertEquals(200, door(other))

        val again = splice(server, "key", "revoke-person", "olga@team.example")
        assertEquals(0, again.code, again.toString())
        assertTrue(again.out.contains("no live key"), again.out)
    }

    @Test
    fun `person set keeps a person's own caps, each one until it is set again`() {
        val first = splice(server, "person", "set", "Carl@team.example", "--inflight", "1")
        assertEquals(0, first.code, first.toString())
        val shown = listOf("carl@team.example", "in flight at most 1", "as [server.limits] sets")
        assertTrue(shown.all(first.out::contains), first.out)
        val second = splice(server, "person", "set", "carl@team.example", "--rpm", "0")
        assertEquals(0, second.code, second.toString())
        assertTrue(listOf("in flight at most 1", "a minute not capped").all(second.out::contains), second.out)
    }

    @Test
    fun `person set --upstream keeps the upstream a person prefers, and refuses a name no provider lists`() {
        val set = splice(server, "person", "set", "dana@team.example", "--upstream", "gpu-2", "--inflight", "2")
        assertEquals(0, set.code, set.toString())
        assertTrue(listOf("dana@team.example", "in flight at most 2", "upstream gpu-2").all(set.out::contains), set.out)

        val unknown = splice(server, "person", "set", "dana@team.example", "--upstream", "gpu-9")
        assertEquals(1, unknown.code, unknown.toString())
        assertTrue(listOf("gpu-9", "gpu-1, gpu-2").all(unknown.err::contains), unknown.err)

        val kept = splice(server, "person", "set", "dana@team.example", "--rpm", "30")
        assertEquals(0, kept.code, kept.toString())
        assertTrue(kept.out.contains("upstream gpu-2"), "a refused name or a later cap changed it: ${kept.out}")

        val back = splice(server, "person", "set", "dana@team.example", "--upstream", "")
        assertEquals(0, back.code, back.toString())
        assertTrue(back.out.contains("upstream the least busy"), back.out)
    }

    @Test
    fun `words a verb cannot read print the usage and reach no daemon`() {
        val unreadable = listOf(
            listOf("key", "create"),
            listOf("key", "create", "--person"),
            listOf("key", "list", "everyone"),
            listOf("key", "revoke"),
            listOf("person", "set", "carl@team.example"),
            listOf("person", "set", "carl@team.example", "--inflight", "many"),
            listOf("keys"),
        )
        for (words in unreadable) {
            val ran = splice(server, *words.toTypedArray())
            assertEquals(2, ran.code, "$words: $ran")
            assertTrue(ran.err.startsWith("usage: splice server key create --person <email>"), "$words: $ran")
            assertEquals("", ran.out, "$words")
        }
    }

    @Test
    fun `a person that is no email is refused in the daemon's words, with nothing on stdout`() {
        val ran = splice(server, "key", "create", "--person", "not-an-email", "--json")
        assertEquals(1, ran.code, ran.toString())
        assertEquals("", ran.out)
        assertTrue(ran.err.startsWith("splice server: ") && ran.err.contains("email"), ran.err)
    }

    @Test
    fun `a daemon whose splice_toml has no server table says so to a verb`() {
        val ran = splice(plain, "key", "list")
        assertEquals(1, ran.code, ran.toString())
        assertTrue(ran.err.contains("splice.toml has no [server]"), ran.err)
    }
}

/** One run of a verb: its exit code, and what it printed on stdout and stderr. */
private data class Run(val code: Int, val out: String, val err: String) {
    override fun toString(): String = "exit $code\n--- out\n$out\n--- err\n$err"
}
