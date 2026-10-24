// NEW: LAN share, round 13 of PR #419's review. The goal: importing another machine's commands writes those commands
// and nothing else into splice.toml. What that machine describes arrives over the network, and before its certificate
// is pinned anyone answering at its address could describe it; so a value that would end its line and open a table of
// its own (a family, a tier name) is refused, and splice.toml is left as it was. `splice remote`'s own write, through
// `splice add`'s machinery, over a hermetic SPLICE_CONFIG and a fake network.
package splice.configuration.add

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

private const val RELAY = "https://torad-server.lan:3097/share/v1/heads/claudex"

// A table a crafted value would open in the importing machine's file: sharing turned on.
private const val OPENS_A_TABLE = "\n[share]\nenabled = true\n#"

class RemoteHeadAddTest {
    @TempDir
    lateinit var home: Path

    private val lines = mutableListOf<String>()

    private val env by lazy {
        EnvReader { if (it == "SPLICE_CONFIG") home.resolve("splice.toml").toString() else null }
    }

    /** The importing machine's splice.toml, with the sharing machine already in `[remotes]`, as the import writes it
     *  before its heads. */
    private val file by lazy {
        home.resolve("splice.toml").also {
            val pin = "sha256:" + "a".repeat(64)
            val remote = "[remotes.torad-server]\nurl = \"https://torad-server.lan:3097\"\nfingerprint = \"$pin\"\n"
            Files.writeString(it, remote)
        }
    }

    private fun adder(): RemoteHeadAdd {
        val out = TerminalOutput { lines += it }
        val ports = AddPorts(
            login = { _, _, _ -> error("an imported head never starts a sign-in") },
            install = { _, _ -> AddLinked.Linked },
            restart = { true },
            daemonUp = { false },
            prompt = { _, default -> default },
        )
        return RemoteHeadAdd(AddCommand(out, out, AddChecks(out, { _, _, _, _ -> AddHttpReply(200, "{}") }), ports))
    }

    private fun imported(family: String? = "openai", slot: String = "opus"): Boolean {
        val head = RemoteHead(
            remote = "torad-server",
            remoteKey = "claudex",
            key = "claudex",
            command = "claudex",
            baseUrl = RELAY,
            models = listOf(RemoteModel("gpt-5", "GPT-5", 400_000L, listOf(slot))),
            family = family,
        )
        return runBlocking { adder().add(head, env) }
    }

    @Test
    fun `a head described plainly is written with its family and tier`() {
        file

        assertTrue(imported(), lines.joinToString("\n"))

        val written = TopologyLoader.parse(Files.readString(file))
        assertTrue("claudex" in written.heads, "the head is written: ${lines.joinToString("\n")}")
        assertEquals(false, written.share.enabled)
    }

    @Test
    fun `a family that would open a table of its own is refused, and splice toml is left as it was`() {
        val before = Files.readString(file)

        assertFalse(imported(family = "openai\"$OPENS_A_TABLE"), lines.joinToString("\n"))

        assertEquals(before, Files.readString(file))
    }

    @Test
    fun `a tier name that would open a table of its own is refused, and splice toml is left as it was`() {
        val before = Files.readString(file)

        assertFalse(imported(slot = "opus = \"gpt-5\" }$OPENS_A_TABLE"), lines.joinToString("\n"))

        assertEquals(before, Files.readString(file))
    }
}
