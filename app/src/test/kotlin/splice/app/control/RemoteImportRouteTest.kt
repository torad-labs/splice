// NEW: LAN share, round 16 of PR #419's review. The goal: whatever host and port the Sharing page sends to import a
// machine, typed in its box or carried by a found machine's card, Marcos is answered in words: what it could not read
// as a 400, naming a port no machine listens on, or what `splice remote add` said trying the address; never a 500. A
// found card carries its host as discovery read it, an IPv6 address with brackets or without, with a scope or not, and
// its port; no list of examples covers those, so hosts are drawn from the pieces they are made of, with no letters that
// would send a draw to a name server, and each draw carries a port, so none reaches a splice sharing on this machine's
// own. The real ControlServer over HTTP (SharingRoutesFixture).
package splice.app.control

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import splice.core.testing.TestPorts
import kotlin.random.Random

// why: a fixed seed, so a failing draw is the same draw next time.
private const val IMPORT_SEED = 16

// why: enough draws that each piece lands alone, first and last, with a port that is one and one that is not.
private const val IMPORT_RUNS = 40

// why: the highest port there is.
private const val PORT_MAX = 65_535

// why: pieces of a host a found card or a person's box can carry: IPv6 with and without brackets and scopes, loopback,
// and what no address holds.
private val HOST_PIECES = listOf(
    "[::1]", "::1", "fe80::1", "%en0", "%25en0", "[", "]", "::", "127.0.0.1", ":", "%zz", "é", " ", "/", "@",
)

internal class RemoteImportRouteTest : SharingRoutesFixture() {

    /** A port nothing listens on, so an address that reads is asked and answers nothing at once. */
    private val closed = TestPorts.reserve()

    @Test
    fun `whatever host and port the page sends to import a machine, it is answered in words, never a 500`() {
        val ports = listOf(closed, closed, 0, -1, PORT_MAX + 1, 70_000, Int.MAX_VALUE)
        val random = Random(IMPORT_SEED)
        serve { call ->
            repeat(IMPORT_RUNS) { run ->
                val host = (0 until random.nextInt(1, 4))
                    .joinToString("") { HOST_PIECES[random.nextInt(HOST_PIECES.size)] }
                val port = ports[random.nextInt(ports.size)]
                val body = buildJsonObject {
                    put("host", host)
                    put("port", port)
                }

                val reply = call(HttpMethod.Post, "/api/remotes", body.toString(), true)

                val text = reply.bodyAsText()
                val at = "run $run, '$host' at $port"
                when (reply.status.value) {
                    400 -> assertTrue(json(text).getValue("error").jsonPrimitive.content.isNotBlank(), "$at: $text")
                    200 -> assertTrue(json(text).getValue("said").jsonArray.isNotEmpty(), "$at: what it tried: $text")
                    else -> fail("$at: answered ${reply.status.value}: $text")
                }
                if (port !in 1..PORT_MAX) assertEquals(400, reply.status.value, "$at: no port: $text")
            }
        }
    }
}
