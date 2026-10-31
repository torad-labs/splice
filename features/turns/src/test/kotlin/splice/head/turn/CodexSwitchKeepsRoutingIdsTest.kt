package splice.head.turn

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Spec section 11, codex-rs parity: `session-id` and `thread-id` are cache-routing hints and the account is the
 *  ChatGPT-Account-ID header, so a switch at a turn boundary changes only the account header. */
class CodexSwitchKeepsRoutingIdsTest(@param:TempDir private val root: Path) {
    @Test
    fun `a switch changes the account header and leaves the session and thread ids alone`() = runTest {
        val rig = AccountTurnRig(root)
        try {
            rig.start()
            rig.messages(sessionId = "codex-session").also { it.bodyAsText() }
            rig.exhaustPrimary()
            rig.messages(sessionId = "codex-session").also { it.bodyAsText() }

            assertEquals(listOf("primary-id", "backup-id"), rig.accountHeaders())
            val (first, second) = rig.routingHeaders()
            assertEquals("codex-session", first.first)
            assertNotNull(first.second)
            assertEquals(first, second, "session-id and thread-id must be identical on both sides of the switch")
        } finally {
            rig.close()
        }
    }
}
