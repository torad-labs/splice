// What a splice launch that registers as a peer hands Claude Code: the peer server as one word right after the binary,
// in front of the words the session was given, so each of those keeps the meaning it had. Before Nov 26 the server went
// after them as a flag and its value, and an option the person left without its value took `--mcp-config` as that
// value, the config's JSON becoming the session's first message, where Claude Code would have refused the line.
package splice.app.cli.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.util.EnvReader

class PeerMcpLaunchTest {
    private val peer = PeerMcpLaunch(EnvReader(System::getenv))

    @Test
    fun `a peer launch whose last option lacks its value keeps that option last`() {
        for (last in listOf("--system-prompt", "--model", "-n")) {
            val argv = peer.inFront(listOf("/bin/claude", "--settings", "{}", "--verbose", last))
            assertEquals(last, argv.last(), "nothing fills its value: $argv")
            assertEquals(listOf("--settings", "{}", "--verbose", last), argv.drop(2), "the words as given: $argv")
        }
    }

    @Test
    fun `the peer server is one word right after the binary, naming the peer server alone`() {
        val argv = peer.inFront(listOf("/bin/claude", "-p", "--", "--draw", "a chart"))
        assertEquals("/bin/claude", argv.first())
        val config = argv[1].substringAfter("--mcp-config=")
        assertEquals(
            setOf("splice-peers"),
            Json.parseToJsonElement(config).jsonObject.getValue("mcpServers").jsonObject.keys,
        )
        assertEquals(listOf("-p", "--", "--draw", "a chart"), argv.drop(2))
    }
}
