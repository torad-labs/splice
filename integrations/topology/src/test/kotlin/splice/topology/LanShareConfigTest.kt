// LAN share's two tables in splice.toml (docs/specs/lan-share.md): `[share]` on the machine that offers its heads to
// its other machines, and `[remote.<command>]` on a machine that runs one of them, holding nothing but the address.
// What each reads as, and what boot refuses, by key path, before anything listens or launches.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.RemoteAddress
import splice.core.topology.RemoteAddressRead
import splice.core.topology.RemoteAddresses
import splice.core.util.Cancellables
import splice.core.util.TopologyRefusal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

class LanShareConfigTest {
    @TempDir
    lateinit var root: Path

    private val head = """
        [providers.demo]
        dialect = "openai-chat"
        base_url = "https://demo.invalid"
        auth = { kind = "api-key", env = "DEMO_KEY" }
        [[providers.demo.models]]
        id = "m"
        context_window = 128000

        [heads.local]
        provider = "demo"
        port = 3101
        discovery_prefix = "claude-local--"
        pinned_model = "m"
    """.trimIndent()

    private fun boot(text: String): Result<splice.core.topology.Topology> {
        val path = root.resolve("splice.toml").also { Files.writeString(it, "$head\n\n${text.trimIndent()}\n") }
        return Cancellables.runCatchingCancellable { TopologyLoader.loadForBoot(path).topology }
    }

    /** The key paths boot refused, or empty when it booted. */
    private fun refused(text: String): List<String> =
        boot(text).exceptionOrNull()?.let { (it as TopologyRefusal).findings.map { finding -> finding.path } }
            ?: emptyList()

    @Test
    fun `a share table that only turns sharing on listens on 3097`() {
        val share = boot("[share]\nenabled = true").getOrThrow().share
        assertTrue(share.enabled)
        assertEquals(3097, share.port)
        assertFalse(boot("").getOrThrow().share.enabled, "sharing is off unless the file turns it on")
    }

    @Test
    fun `a share port outside the TCP range, or one a head here holds, refuses boot at share port`() {
        assertEquals(listOf("share.port"), refused("[share]\nenabled = true\nport = 70000"))
        assertTrue("share.port" in refused("[share]\nenabled = true\nport = 3101"))
        val off = refused("[share]\nenabled = false\nport = 3101")
        assertEquals(emptyList<String>(), off, "a share that is off holds no port")
    }

    @Test
    fun `a remote command boots holding its address and nothing else`() {
        val topology = boot("[remote.claudex]\nurl = \"http://torad-server.lan:3097/claudex?auth=work\"").getOrThrow()
        assertEquals("http://torad-server.lan:3097/claudex?auth=work", topology.remote.getValue("claudex").url)
    }

    @Test
    fun `a remote command a head here already runs refuses boot at its remote key`() {
        assertEquals(listOf("remote.local"), refused("[remote.local]\nurl = \"http://torad:3097/claudex\""))
    }

    // Marcos, 2026-10-25: plain `claude` runs on the server's Claude logins, so `claude` may name a remote command.
    @Test
    fun `a remote claude boots holding its address`() {
        val topology = boot("[remote.claude]\nurl = \"http://torad:3097/claude-splice\"").getOrThrow()
        assertEquals("http://torad:3097/claude-splice", topology.remote.getValue("claude").url)
    }

    @Test
    fun `a remote address that does not read refuses boot at its url`() {
        assertEquals(listOf("remote.claudex.url"), refused("[remote.claudex]\nurl = \"torad:3097/claudex\""))
    }

    @Test
    fun `any address built from a server, a head, a login and models reads back as exactly those`() {
        val random = Random(SEED)
        repeat(CASES) {
            val parts = generated(random)
            val read = RemoteAddresses.read(parts.text)
            assertEquals(RemoteAddressRead.Read(parts.expected), read, parts.text)
            val address = (read as RemoteAddressRead.Read).address
            val login = parts.expected.auth?.let { "/@$it" }.orEmpty()
            assertEquals("${parts.expected.server}/${parts.expected.head}$login", address.turns, parts.text)
        }
    }

    @Test
    fun `any address bent out of that shape is refused, saying what is wrong`() {
        val random = Random(SEED)
        repeat(CASES) {
            val good = generated(random).text
            val bent = bends.random(random)(good)
            val read = RemoteAddresses.read(bent)
            assertTrue(read is RemoteAddressRead.Refused, "read $bent as $read")
            assertTrue((read as RemoteAddressRead.Refused).why.isNotBlank(), bent)
        }
    }

    /** Ways to bend a good address into one that is not: each breaks exactly one part of the shape. */
    private val bends: List<(String) -> String> = listOf(
        { it.substringAfter("://") }, // no scheme
        { "ftp://" + it.substringAfter("://") }, // a scheme splice does not speak
        { it.substringBefore('?').substringBeforeLast('/') + "/" }, // no head
        { it.substringBefore('?') + "/extra" + it.substringAfter('?', "").let { q -> if (q.isEmpty()) "" else "?$q" } },
        { it + (if ('?' in it) "&" else "?") + "usage=work" }, // a param the server does not read
        { it + (if ('?' in it) "&" else "?") + "auth=" }, // an empty login
        { it + (if ('?' in it) "&" else "?") + "models=" }, // no models
        { it + (if ('?' in it) "&" else "?") + "auth=a&auth=b" }, // a login named twice
        { "$it#frag" }, // a fragment
        { it.replaceFirst("://", "://someone@") }, // credentials in the address
        { it.substringBefore("://") + "://" + "/" + it.substringAfter("://").substringAfter('/') }, // no server
    )

    private data class Generated(val text: String, val expected: RemoteAddress)

    private fun generated(random: Random): Generated {
        val scheme = listOf("http", "https").random(random)
        val host = listOf(
            "torad-server.lan",
            "torad-server",
            "192.168.${random.nextInt(256)}.${random.nextInt(1, 255)}",
            "[fd00::${random.nextInt(1, 0xffff).toString(16)}]",
            "box-${random.nextInt(100)}.local",
        ).random(random)
        val port = if (random.nextBoolean()) ":${random.nextInt(1, 65536)}" else ""
        val head = word(random, "abcdefghijklmnopqrstuvwxyz", "abcdefghijklmnopqrstuvwxyz0123456789-")
        val auth = if (random.nextBoolean()) word(random, LOWER_DIGITS, "$LOWER_DIGITS._-") else null
        val models = if (random.nextBoolean()) {
            List(random.nextInt(1, 4)) { word(random, LOWER_DIGITS, "$LOWER_DIGITS.:_-") }.distinct()
        } else {
            emptyList()
        }
        val named = models.takeIf { it.isNotEmpty() }?.let { "models=${it.joinToString(",")}" }
        val params = listOfNotNull(auth?.let { "auth=$it" }, named).shuffled(random)
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val server = "$scheme://$host$port"
        return Generated("$server/$head$query", RemoteAddress(server, head, auth, models))
    }

    private fun word(random: Random, first: String, rest: String): String =
        first.random(random) + (0 until random.nextInt(0, 12)).map { rest.random(random) }.joinToString("")
}

private const val SEED = 20261024
private const val CASES = 500
private const val LOWER_DIGITS = "abcdefghijklmnopqrstuvwxyz0123456789"
