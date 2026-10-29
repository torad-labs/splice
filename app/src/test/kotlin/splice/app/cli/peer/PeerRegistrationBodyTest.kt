// Oct 28, 2026 (Marcos: "add the ability for anyone or anything to register to send and receive messages via splice
// peer. For anything other than claude code, we'll keep it as a manual thing agents have to do"). A bot, a script or
// another harness registers by hand, and `--owner <pid>` names the process the peer lives and dies with, where the
// register could only find a codex or claude ancestor. A pid that names no running process is refused before any call.
package splice.app.cli.peer

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.util.JsonScalars
import splice.sessions.peer.owner.PeerOwnerProcesses

class PeerRegistrationBodyTest {
    @Test
    fun `--owner names the process the peer belongs to`() {
        val pid = ProcessHandle.current().pid()
        val body = PeerRegistrationBody.build("grokbot", mapOf("--owner" to pid.toString()))
        assertNotNull(body, "a live pid is accepted")
        val owner = checkNotNull(body)["owner"] as JsonObject
        assertEquals(pid.toString(), JsonScalars.str(owner, "pid"))
        val expected = checkNotNull(PeerOwnerProcesses().capture(pid))
        assertEquals(expected.procStart, JsonScalars.str(owner, "procStart"))
        assertEquals(expected.pidDomain, JsonScalars.str(owner, "pidDomain"))
    }

    @Test
    fun `an --owner that names no running process is refused`() {
        assertNull(PeerRegistrationBody.build("grokbot", mapOf("--owner" to "4194303999")))
        assertNull(PeerRegistrationBody.build("grokbot", mapOf("--owner" to "bot")))
    }
}
