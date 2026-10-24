// NEW: LAN share, round 11 of PR #419's review. The goal: Marcos revokes a machine, or turns its Sessions switch, by the
// name it joined under however he spells it, phase 1's capitals included, and a machine that joins under a revoked
// machine's name starts with no last seen and no turns of its own. ShareClients over a real clients.json in a temp
// folder, as the daemon and `splice share revoke` both use it.
package splice.share

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ShareClientsTest {
    @TempDir
    lateinit var tmp: Path

    private val file by lazy { tmp.resolve("clients.json") }
    private val clients by lazy { ShareClients(file) }

    @Test
    fun `a machine is revoked and its Sessions switch turned by its name however it is spelled`() {
        val token = clients.join("Torad-Server", "192.168.1.236")

        assertTrue(clients.allowSessions("Torad-Server", true), "the switch finds it as the machine calls itself")
        assertTrue(clients.all().single().sessions, "and turns it on")
        assertTrue(clients.revoke("TORAD-SERVER"), "revoke finds it however it is typed")
        assertNull(clients.holder(token), "and its token stops working")
    }

    @Test
    fun `a machine phase 1 booked with capitals is revoked by the name it is listed under now`() {
        Files.writeString(
            file,
            """{"clients":[{"name":"Torad-Server","token_sha256":"${"0".repeat(64)}",""" +
                """"address":"192.168.1.236","joined_at":"2026-10-22T12:00:00Z"}]}""",
        )

        assertTrue(clients.allowSessions("torad-server", true), "its switch turns")
        assertTrue(clients.revoke("torad-server"), "and it is revoked")
        assertEquals(emptyList<ShareClient>(), clients.all())
    }

    @Test
    fun `a machine that joins under a revoked machine's name starts with no last seen or turns of its own`() {
        clients.join("mini", "192.168.1.243")
        clients.activity.heard("mini", turn = true)
        clients.revoke("mini")

        clients.join("mini", "192.168.1.99")

        assertTrue(clients.activity.now().none { it.name == "mini" }, "${clients.activity.now()}")
    }

    @Test
    fun `a machine that joins again while still joined keeps its last seen and turns`() {
        clients.join("mini", "192.168.1.243")
        clients.activity.heard("mini", turn = true)

        clients.join("mini", "192.168.1.243")

        val seen = clients.activity.now().single { it.name == "mini" }
        assertEquals(1, seen.turnsOn(clients.activity.today()), "a re-import is the same machine")
    }
}
