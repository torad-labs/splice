// NEW: LAN share, phase 2, milestone 4 (docs/specs/lan-share.md). The Sharing page's rows for this machine THROUGH A
// REAL ControlServer over HTTP with the mgmt key: GET /api/share as the file holds it, PUT /api/share turning the real
// share listener on and off with no restart, the offered commands stored as the page chose them, and the refusals in
// words. Milestone 5: a machine that turns sharing on is found by one looking on the network, over real multicast DNS
// and the real TLS probe, and is not found once it turns it off. The server is SharingRoutesFixture's; the page's
// machine cards and their Sessions switches are SharingMachinesTest's.
package splice.app.control

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.core.topology.ShareConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.share.MachinesJoined
import splice.share.SessionsSwitch
import splice.share.ShareHostName
import splice.share.ShareListener
import splice.share.ShareStart
import splice.share.ShareState
import splice.share.SharedCatalogs
import splice.share.SharedQuotas
import splice.share.remote.RemoteFound
import splice.share.remote.RemoteHttp
import splice.share.remote.RemoteJoin
import splice.share.remote.RemoteJoined
import splice.share.remote.RemoteLinks
import splice.share.remote.RemoteTrust
import splice.share.remote.RemotesStore
import splice.topology.TopologyLoader
import java.nio.file.Files

internal class SharingRoutesTest : SharingRoutesFixture() {

    @Test
    fun `the read is guarded and says the file's sharing, each command, and why one cannot be offered`() {
        serve { call ->
            assertEquals(401, call(HttpMethod.Get, "/api/share", null, false).status.value, "the read is guarded")
            val view = read(call)
            val sharing = view.getValue("sharing").jsonObject
            assertFalse(sharing.getValue("enabled").jsonPrimitive.boolean)
            assertFalse(sharing.getValue("listening").jsonPrimitive.boolean)
            val commands = commands(view)
            assertEquals(setOf("one", "two", "mine"), commands.keys)
            assertEquals("claude-one", commands.getValue("one").getValue("command").jsonPrimitive.content)
            assertTrue(commands.getValue("one").getValue("offered").jsonPrimitive.boolean, "no list offers them all")
            assertFalse(commands.getValue("mine").getValue("offered").jsonPrimitive.boolean)
            assertEquals(
                "it forwards the caller's own Claude login, so there is nothing to share",
                commands.getValue("mine").getValue("why").jsonPrimitive.content,
            )
            assertEquals(0, view.getValue("joined").jsonArray.size)
            assertEquals(0, view.getValue("remotes").jsonArray.size)
        }
    }

    @Test
    fun `turning sharing on opens the listener at once, and off closes it, with no restart`() {
        val port = TestPorts.reserve()
        serve { call ->
            assertEquals(401, call(HttpMethod.Put, "/api/share", "{}", false).status.value, "the write is guarded")
            val on = put(call, "{\"enabled\":true,\"port\":$port}")
            assertTrue(on.getValue("ok").jsonPrimitive.boolean, on.toString())
            assertTrue(listening(on), on.toString())
            val pin = on.getValue("share").jsonObject.getValue("sharing").jsonObject.getValue("fingerprint")
            assertTrue(pin.jsonPrimitive.content.isNotBlank(), "the pin a joining machine compares")
            assertTrue(answers(port), "the listener is open on the port the page asked for")
            val stored = TopologyLoader.parse(Files.readString(file)).share
            assertTrue(stored.enabled)
            assertEquals(port, stored.port)

            val off = put(call, "{\"enabled\":false}")
            assertFalse(listening(off), off.toString())
            assertFalse(answers(port), "the listener closed")
            assertFalse(TopologyLoader.parse(Files.readString(file)).share.enabled)
        }
    }

    @Test
    fun `a machine sharing is found on its network at its port and certificate, and not once it stops`() {
        val port = TestPorts.reserve()
        serve { call ->
            val on = put(call, "{\"enabled\":true,\"port\":$port}")
            val sharing = on.getValue("share").jsonObject.getValue("sharing").jsonObject
            val pin = sharing.getValue("fingerprint").jsonPrimitive
            val found = looked(port) { it != null }
            assertEquals(ShareHostName().short(), found?.info?.server)
            assertEquals(pin.content, found?.info?.fingerprint, "the certificate met is the one the page shows")

            put(call, "{\"enabled\":false}")
            assertNull(looked(port) { it == null }, "a machine that stopped sharing is no longer advertised")
        }
    }

    @Test
    fun `one command offered is stored by name, and every one offered is stored as none named`() {
        serve { call ->
            val one = put(call, "{\"heads\":[\"one\"]}")
            val commands = commands(one.getValue("share").jsonObject)
            assertTrue(commands.getValue("one").getValue("offered").jsonPrimitive.boolean)
            assertFalse(commands.getValue("two").getValue("offered").jsonPrimitive.boolean)
            assertEquals(listOf("one"), TopologyLoader.parse(Files.readString(file)).share.heads)

            put(call, "{\"heads\":[\"one\",\"two\"]}")
            assertNull(TopologyLoader.parse(Files.readString(file)).share.heads, "a command added later is offered too")
        }
    }

    @Test
    fun `a body the route cannot read is a 400 and leaves the file byte-identical`() {
        serve { call ->
            val reply = call(HttpMethod.Put, "/api/share", "{nope", true)
            assertEquals(400, reply.status.value)
            assertTrue(json(reply.bodyAsText()).getValue("error").jsonPrimitive.content.startsWith("the body must be"))
            assertEquals(shareToml, Files.readString(file))
        }
    }

    @Test
    fun `revoking a machine that never joined says so in words`() {
        serve { call ->
            val reply = call(HttpMethod.Delete, "/api/share/machines/nobody", null, true)
            assertEquals(200, reply.status.value)
            val body = json(reply.bodyAsText())
            assertFalse(body.getValue("ok").jsonPrimitive.boolean)
            val said = body.getValue("said").jsonArray.single().jsonObject
            assertEquals("no machine named 'nobody' has joined", said.getValue("text").jsonPrimitive.content)
            assertTrue(said.getValue("bad").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `unwired, the read answers 503 by name`() {
        serve(wired = false) { call ->
            val reply = call(HttpMethod.Get, "/api/share", null, true)
            assertEquals(503, reply.status.value)
            assertEquals(
                "sharing is not wired in this daemon",
                json(reply.bodyAsText()).getValue("error").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `an address typed in any form the CLI takes is asked at its own port, and one that is no address is a 400`() {
        serve { call ->
            val v6 = call(HttpMethod.Post, "/api/remotes", "{\"host\":\"[::1]:1\"}", true)
            assertEquals(200, v6.status.value, v6.bodyAsText())
            val said = json(v6.bodyAsText()).getValue("said").jsonArray
                .joinToString(" ") { it.jsonObject.getValue("text").jsonPrimitive.content }
            assertTrue("[::1]:1 " in said, "asked at the address and the port typed: $said")

            val bad = call(HttpMethod.Post, "/api/remotes", "{\"host\":\"not an address\"}", true)
            assertEquals(400, bad.status.value, bad.bodyAsText())
            val error = json(bad.bodyAsText()).getValue("error").jsonPrimitive.content
            assertTrue("not an address" in error, "it names what it could not read: $error")
        }
    }

    /** A machine sharing on [port] that offers no command, as torad does once it stops offering one: on its own state
     *  folder, apart from this machine's. */
    private fun sharingNothing(port: Int): ShareListener {
        val vars = mapOf("SPLICE_STATE_DIR" to tmp.resolve("there").toString(), "HOME" to tmp.toString())
        val paths = StatePaths(envReader = EnvReader { vars[it] })
        val there = ShareListener(
            ShareStart(Topology(), paths, MgmtKey(paths), ConfigService(paths)) { },
            SharedCatalogs { null },
            SharedQuotas { null },
            paths,
            MachinesJoined { emptySet() },
            { },
        )
        runBlocking { there.follow(ShareConfig(enabled = true, port = port)) }
        return there
    }

    @Test
    fun `a command whose address ends in a slash reads not answering while its machine does not serve it`() {
        val port = TestPorts.reserve()
        val there = sharingNothing(port)
        val watching = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val info = checkNotNull(RemoteHttp(RemoteTrust(null, "torad")).info("127.0.0.1", port).get())
            val joined = RemoteJoin("mini").join(RemoteFound("127.0.0.1", port, info)) as RemoteJoined.Joined
            val url = joined.http.base("127.0.0.1", port)
            // A relayed command's base_url, edited by hand to end in a slash.
            Files.writeString(
                file,
                """
                [remotes.torad]
                url = "$url"
                fingerprint = "${joined.pin}"

                [providers.torad-kimi]
                dialect = "anthropic-passthrough"
                base_url = "$url/share/v1/heads/kimi/"
                auth = { kind = "splice-remote", remote = "torad" }

                [heads.torad-kimi]
                provider = "torad-kimi"
                port = 3911
                discovery_prefix = "claude-torad-kimi--"
                pinned_model = "kimi-1"
                """.trimIndent() + "\n",
            )
            val state = ShareState(StatePaths(envReader = env))
            val store = RemotesStore(state.remotesFile)
            store.save("torad", joined.token)
            val booted = TopologyLoader.parse(Files.readString(file))
            val links = RemoteLinks(booted, store, SessionsSwitch(state, MachinesJoined { booted.remotes.keys }))
            links.watch(watching, { })
            runBlocking { withTimeout(SHARE_TIMEOUT_MS) { while (links.reach().isEmpty()) delay(SHARE_POLL_MS) } }
            assertEquals(mapOf("torad-kimi" to "torad"), links.notAnswering(), "the heads route reads it not answering")

            serve(remotes = links) { call ->
                val torad = read(call).getValue("remotes").jsonArray.single().jsonObject
                val kimi = torad.getValue("commands").jsonArray.single().jsonObject
                assertTrue(kimi.getValue("down").jsonPrimitive.boolean, "and so does the Sharing page: $kimi")
            }
        } finally {
            watching.cancel()
            runBlocking { there.stop() }
        }
    }
}
