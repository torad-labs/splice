// PR #247's fifth review (Oct 26, 2026): a key rotation replaces the MCP access key, and a session launched before it
// keeps the key its launch handed it until it ends, so its hosted MCP servers refuse it until it is relaunched. These
// are the facts `splice sessions relaunch` asks of each running session. The files are the ones splice's launch path
// writes: the wrapped launch's file (HostedMcpOverlay) and a head's `.claude.json` entries (McpSharing's rewrite), and
// the hub's launch file, whose writer needs a running front and is written here in its format.
package splice.client.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

// why: a stdio server whose arguments name no place, which splice hosts once for every session, and a remote server
// of the person's own, with his own token, which splice passes through as he wrote it
private const val USER_SERVERS = """{
  "exa": {"command": "npx", "args": ["-y", "exa-mcp"]},
  "docs": {"type": "http", "url": "https://mcp.example.com/mcp", "headers": {"Authorization": "Bearer his-own"}}
}"""
private const val OWN_ONLY = """{
  "docs": {"type": "http", "url": "https://mcp.example.com/mcp", "headers": {"Authorization": "Bearer his-own"}}
}"""

private const val HOUR = 3600L

class McpKeyHoldersTest {
    @TempDir
    lateinit var tmp: Path

    private var management = "synthetic-old"
    private val state: Path get() = tmp.resolve("state")
    private val home: Path get() = tmp.resolve("home")
    private val key by lazy { McpAccessKey(stateDir = state) { management } }
    private val sharing by lazy {
        McpSharing(enabled = true, exclude = emptySet(), endpointPrefix = "http://127.0.0.1:3096/mcp/", bearer = key)
    }
    private val holders by lazy { McpKeyHolders(state) }

    @Test
    fun `a wrapped session started before the MCP key was replaced holds it, though its head's file has the new one`() {
        val file = wrappedLaunch()
        val before = Instant.now().minusSeconds(HOUR)
        rotate()
        assertEquals(file, wrappedLaunch(), "the next launch of that head rewrote the one file with the new key")
        val after = Instant.now().plusSeconds(1)

        assertEquals(true, holders.replaced(before, mcpConfig(file), home))
        assertEquals(false, holders.replaced(after, mcpConfig(file), home), "started since, it holds the new key")
    }

    @Test
    fun `a head's own session holds the replaced key in the entries its home carries, when it started first`() {
        val headHome = tmp.resolve(".claude-kimi")
        writeServers(headHome, sharing.plan(objectOf(USER_SERVERS), "claude-kimi").rewritten)
        val before = Instant.now().minusSeconds(HOUR)
        rotate()
        writeServers(headHome, sharing.plan(objectOf(USER_SERVERS), "claude-kimi").rewritten)
        val after = Instant.now().plusSeconds(1)

        assertEquals(true, holders.replaced(before, listOf("--resume", "s-1"), headHome))
        assertEquals(false, holders.replaced(after, listOf("--resume", "s-1"), headHome))
    }

    @Test
    fun `a session the MCP hub carries holds the replaced key in its launch file, when it started first`() {
        val bearer = key()
        val launchFile = state.resolve("mcp-hub").resolve("launch-4242.json")
        Files.writeString(
            launchFile,
            buildJsonObject {
                put(
                    "mcpServers",
                    objectOf(
                        """{"exa": {"type": "http", "url": "http://127.0.0.1:3097/mcp/exa",
                            "headers": {"Authorization": "Bearer $bearer", "$HUB_LAUNCH_HEADER": "4242"}}}""",
                    ),
                )
            }.toString(),
        )
        val before = Instant.now().minusSeconds(HOUR)
        rotate()

        assertEquals(true, holders.replaced(before, listOf("--mcp-config=$launchFile"), home))
    }

    @Test
    fun `a session splice handed no hosted entry holds no MCP key of splice's`() {
        writeServers(home, objectOf(OWN_ONLY))
        key()
        val before = Instant.now().minusSeconds(HOUR)
        rotate()

        assertEquals(false, holders.replaced(before, emptyList(), home), "his own server, with his own token")
        assertEquals(
            false,
            McpKeyHolders(tmp.resolve("no-mcp-key")).replaced(before, emptyList(), home),
            "a splice that never published an MCP key replaced none",
        )
    }

    // A relaunch refreshes only what a launch writes: splice's own files, rewritten at each launch. A copy of splice's
    // entries in a file of his own, or written into the command line, would hold the old key after any relaunch.
    @Test
    fun `an MCP config of the person's own is not splice's to refresh, whatever it holds`() {
        val file = wrappedLaunch()
        val copy = Files.copy(file, home.resolve("mine.json"))
        val before = Instant.now().minusSeconds(HOUR)
        rotate()

        assertEquals(false, holders.replaced(before, mcpConfig(copy), home))
        assertEquals(false, holders.replaced(before, listOf("--mcp-config", Files.readString(copy)), home))
    }

    @Test
    fun `what could not be read is not known, never a no`() {
        val file = wrappedLaunch()
        val broken = Files.writeString(state.resolve("wrapped-mcp-claude-broken.json"), "{not json")
        val before = Instant.now().minusSeconds(HOUR)
        rotate()

        assertNull(holders.replaced(before, mcpConfig(broken), home), "a launch file that is not JSON")
        assertNull(holders.replaced(before, null, home), "arguments that could not be read")
        assertNull(holders.replaced(null, mcpConfig(file), home), "a start that is not known")
        assertNull(holders.replaced(before, emptyList(), null), "a config home that is not known")
    }

    private fun objectOf(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** The config home's `.claude.json` with [servers] as its `mcpServers`, as a head's launch writes it. */
    private fun writeServers(dir: Path, servers: JsonObject) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(".claude.json"), buildJsonObject { put("mcpServers", servers) }.toString())
    }

    /** A launch of the wrapped `claude` on the claude-splice head: its one file, written with the key in force. */
    private fun wrappedLaunch(): Path {
        writeServers(home, objectOf(USER_SERVERS))
        return checkNotNull(HostedMcpOverlay(home, sharing, state, LogSink {}).file("claude-splice"))
    }

    /** What `splice rotate-key` does to the MCP access key: the daemon's management key is replaced, and the MCP key
     *  it bound is revoked and a fresh one published. */
    private fun rotate() {
        management = "synthetic-new"
        key.managementReplaced()
    }

    private fun mcpConfig(file: Path): List<String> = listOf("--mcp-config", file.toString())
}
