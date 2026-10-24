// NEW: LAN share, round 15 of PR #419's review. The goal: whatever a sharing machine answers, and whatever a
// hand-edited splice.toml holds, `splice remote`, the Sharing page and boot say it in words a person can act on, as a
// refusal or a finding, and never end on an exception. Five paths read a failure through a catch that does not take it:
// a refused join, a pinned machine whose certificate changed, and a url the URI constructor rejects in the findings, in
// an import's look for phase-1 heads and in a sync. A real sharing listener on loopback refuses joins under drawn names
// and shows a certificate other than the pinned one; urls are drawn from pieces a hand edit can leave, since no list of
// examples covers either.
package splice.app.cli.share

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.peer.PeerExit
import splice.configuration.add.DaemonRestart
import splice.core.terminal.TerminalOutput
import splice.core.testing.TestPorts
import splice.core.topology.API_KEY_WIRE
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.RemoteConfig
import splice.core.topology.Topology
import splice.core.topology.TopologyFindings
import splice.core.util.EnvReader
import splice.share.remote.RemoteJoin
import splice.share.remote.RemoteJoined
import splice.share.remote.RemoteTopology
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

// why: a fixed seed, so a failing draw is the same draw next time.
private const val SAID_SEED = 15

// why: enough draws that every piece lands at the start, the middle and the end of a url or a name several times.
private const val SAID_RUNS = 60

// why: a pin of the right shape that no certificate has.
private const val OTHER_PIN = "sha256:0000000000000000000000000000000000000000000000000000000000000000"

// why: what a sharing machine lets join (ShareServer's clientName): a host name of 64 characters at most.
private val JOINABLE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

// why: pieces of a name a machine might join under: a host name's own, and what no host name holds.
private val NAME_PIECES = listOf("mini", "Mac", "-", ".", "_", "0", " ", "é", "/", "\"", "{", "x".repeat(40))

class RemoteFailuresSaidTest {
    @TempDir
    lateinit var tmp: Path

    private val said = mutableListOf<String>()
    private val lines = TerminalOutput { said += it }

    /** This machine: its splice.toml and state folder in the temp folder. */
    private val here by lazy {
        val vars = mapOf(
            "SPLICE_CONFIG" to tmp.resolve("here/splice.toml").toString(),
            "SPLICE_STATE_DIR" to tmp.resolve("here/state").toString(),
            "HOME" to tmp.resolve("here").toString(),
        )
        EnvReader { vars[it] }
    }

    /** A port nothing listens on, so a url drawn whole reaches no machine and asks no name server. */
    private val asleep = TestPorts.reserve()

    /** Pieces of a url a hand edit can leave: a scheme, this machine's loopback, a port, a path, and characters a url
     *  may not hold. No host of letters, so no draw waits on a name server. */
    private val urlPieces = listOf(
        "https://", "http://", "127.0.0.1", ":$asleep", ":", "/", " ", "{", "}", "|", "^", "%", "%zz", "[", "]",
        "\\", "é", "#", "?", "`", "<",
    )

    private fun drawn(random: Random, pieces: List<String>): String =
        (0 until random.nextInt(1, 7)).joinToString("") { pieces[random.nextInt(pieces.size)] }

    /** [url] as the goal reads it: an https address with a host, or not one at all. */
    private fun httpsWithHost(url: String): Boolean =
        parsed(url)?.let { it.scheme == "https" && it.host != null } == true

    private fun parsed(url: String): URI? = try {
        URI(url)
    } catch (_: URISyntaxException) {
        null
    }

    private fun write(remote: RemoteConfig) {
        val url = remote.url.replace("\\", "\\\\").replace("\"", "\\\"")
        Files.createDirectories(tmp.resolve("here"))
        Files.writeString(
            tmp.resolve("here/splice.toml"),
            "[remotes.x]\nurl = \"$url\"\nfingerprint = \"${remote.fingerprint}\"\n",
        )
    }

    private fun sync(): Int = RemoteMachines(lines, lines, here, DaemonRestart { true }).sync(null)

    @Test
    fun `whatever name this machine joins under, a refusal is said in the sharing machine's words`() {
        LoopbackSharing(tmp, "there").use { machine ->
            val random = Random(SAID_SEED)
            repeat(SAID_RUNS) { run ->
                val client = drawn(random, NAME_PIECES)

                val joined = RemoteJoin(client).join(machine.found)

                if (JOINABLE.matches(client)) {
                    assertTrue(joined is RemoteJoined.Joined, "run $run: '$client' joins: $joined")
                } else {
                    val why = (joined as RemoteJoined.Refused).why
                    assertTrue("did not let this machine join" in why, "run $run: '$client' is refused: $why")
                    assertTrue("name the joining machine" in why, "run $run: in the machine's own words: $why")
                }
            }
        }
    }

    @Test
    fun `a joined machine whose certificate changed is said by the sync, which exits refused`() {
        LoopbackSharing(tmp, "there").use { machine ->
            write(RemoteConfig("https://127.0.0.1:${machine.port}", OTHER_PIN))

            val exit = sync()

            assertNotEquals(PeerExit.DONE, exit, "its rows could not be checked: $said")
            assertTrue(said.any { "certificate changed" in it }, "it says the certificate changed: $said")
        }
    }

    @Test
    fun `whatever url a hand edit leaves, boot finds it, an import looks past it, and a sync says it`() {
        val random = Random(SAID_SEED)
        repeat(SAID_RUNS) { run ->
            val url = drawn(random, urlPieces)
            said.clear()

            val remotes = Topology(remotes = mapOf("x" to RemoteConfig(url, OTHER_PIN)))
            val findings = TopologyFindings.of(remotes, stateBase = tmp).filter { it.path == "remotes.x.url" }
            assertEquals(!httpsWithHost(url), findings.isNotEmpty(), "run $run: '$url' is a finding when not https")

            val relay = "https://127.0.0.1:3097/share/v1/heads/claudex"
            val providers = mapOf("odd" to url, "claudex-torad" to relay).mapValues { (_, base) ->
                ProviderConfig(dialect = Dialect.ANTHROPIC_PASSTHROUGH, baseUrl = base, auth = AuthConfig(API_KEY_WIRE))
            }
            val legacy = RemoteTopology(Topology(providers = providers)).phaseOne(setOf("127.0.0.1"))
            assertEquals("claudex", legacy["claudex-torad"], "run $run: the phase-1 head is found past '$url'")

            write(RemoteConfig(url, OTHER_PIN))
            assertNotEquals(PeerExit.DONE, sync(), "run $run: '$url' reaches no machine: $said")
            val named = parsed(url)?.host != null || said.any { "'$url' is not an address with a host" in it }
            assertTrue(named, "run $run: a url with no host is named: $said")
        }
    }
}
