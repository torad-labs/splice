// NEW: LAN share, round 12 of PR #419's review. The goal: an Off on either of a machine's cards on the Sharing page
// stops sessions crossing with it both ways, however each book spells its name. Phase 1 booked a machine under the name
// it gave (`Torad-Server`), and an import keys `[remotes]` by its lower-case spelling (`torad-server`); each link asks
// the switch by its own book's spelling. The real switch over a real clients.json and remotes.json in a temp folder.
// Round 16: two machines may go by one name, imported here under `<name>` and `<name>-2`, while the clients book holds
// only the one that joined last. Nothing in the books tells which of the two that is, so an Off on any card of that
// name stops the links of every machine that goes by it.
package splice.share

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.share.remote.RemotesStore
import java.nio.file.Files
import java.nio.file.Path

class SessionsSwitchTest {
    @TempDir
    lateinit var tmp: Path

    private val state by lazy {
        val env = mapOf("SPLICE_STATE_DIR" to tmp.resolve("state").toString(), "HOME" to tmp.toString())
        ShareState(StatePaths(envReader = EnvReader { env[it] }))
    }

    /** torad-server in both books: joined here as phase 1 booked it, under the name it gave, and imported here under
     *  `[remotes]`. */
    private fun bothBooks(joinedOn: Boolean, importedOn: Boolean): SessionsSwitch {
        Files.createDirectories(state.clientsFile.parent)
        Files.writeString(
            state.clientsFile,
            """{"clients":[{"name":"Torad-Server","token_sha256":"${"0".repeat(64)}",""" +
                """"address":"192.168.1.236","joined_at":"2026-10-22T12:00:00Z","sessions":$joinedOn}]}""",
        )
        val remotes = RemotesStore(state.remotesFile)
        remotes.save("torad-server", "a token")
        remotes.allowSessions("torad-server", importedOn)
        return SessionsSwitch(state, MachinesJoined { setOf("torad-server") })
    }

    @Test
    fun `an Off on the card of the machine that joined here stops the link this machine opened to it`() {
        val switch = bothBooks(joinedOn = false, importedOn = true)

        assertFalse(switch.onUsed("torad-server"), "this machine's link asks by its [remotes] name")
    }

    @Test
    fun `an Off on the card of the machine this one imported stops the link it opened into this one`() {
        val switch = bothBooks(joinedOn = true, importedOn = false)

        assertFalse(switch.on("Torad-Server"), "its link into this machine asks by the name phase 1 booked")
    }

    /** Two Macs that both call themselves MacBook-Pro, imported here as `macbook-pro` and `macbook-pro-2`, their cards
     *  [firstOn] and [secondOn]. The second also joined this machine last, so the clients book holds it under the name
     *  both give, its card [joinedOn]. */
    private fun twoOfOneName(joinedOn: Boolean, firstOn: Boolean = true, secondOn: Boolean = true): SessionsSwitch {
        Files.createDirectories(state.clientsFile.parent)
        Files.writeString(
            state.clientsFile,
            """{"clients":[{"name":"MacBook-Pro","token_sha256":"${"0".repeat(64)}",""" +
                """"address":"192.168.1.40","joined_at":"2026-10-24T08:00:00Z","sessions":$joinedOn}]}""",
        )
        val remotes = RemotesStore(state.remotesFile)
        remotes.save("macbook-pro", "a token", server = "MacBook-Pro")
        remotes.allowSessions("macbook-pro", firstOn)
        remotes.save("macbook-pro-2", "another token", server = "MacBook-Pro")
        remotes.allowSessions("macbook-pro-2", secondOn)
        return SessionsSwitch(state, MachinesJoined { setOf("macbook-pro", "macbook-pro-2") })
    }

    @Test
    fun `an Off on the joined card of one of two machines of one name stops the links into both`() {
        val switch = twoOfOneName(joinedOn = false)

        assertFalse(switch.onUsed("macbook-pro-2"), "the link into the machine whose card is off")
        assertFalse(switch.onUsed("macbook-pro"), "and into the other: the books cannot tell which of the two joined")
    }

    @Test
    fun `an Off on the imported card of either of two machines of one name stops the one that joined here`() {
        listOf(false to true, true to false).forEach { (firstOn, secondOn) ->
            val switch = twoOfOneName(joinedOn = true, firstOn = firstOn, secondOn = secondOn)

            assertFalse(switch.on("MacBook-Pro"), "cards on: $firstOn, $secondOn: its link into this machine stops")
        }
    }

    @Test
    fun `with every card of two machines of one name on, sessions cross with both`() {
        val switch = twoOfOneName(joinedOn = true)

        assertTrue(switch.onUsed("macbook-pro"), "the link into the first")
        assertTrue(switch.onUsed("macbook-pro-2"), "the link into the second")
        assertTrue(switch.on("MacBook-Pro"), "the link of the one that joined here")
    }
}
