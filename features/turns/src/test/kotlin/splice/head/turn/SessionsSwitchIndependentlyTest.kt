package splice.head.turn

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Spec section 11: the account is decided per turn and sticky per session, so a switch on one session never moves
 *  another. */
class SessionsSwitchIndependentlyTest(@param:TempDir private val root: Path) {
    @Test
    fun `a switch caused by one session does not move a session that is mid-turn on the first account`() =
        runBlocking {
            val rig = AccountTurnRig(root)
            try {
                rig.start()
                rig.holdUpstream()
                val midTurn = CompletableDeferred<Unit>()
                val slow = async(Dispatchers.IO) {
                    rig.streamed(sessionId = "slow", scenario = "hold") { line ->
                        if (line.contains("held")) midTurn.complete(Unit)
                    }
                }
                midTurn.await()

                // Session "fast" meets a full first account and moves to the second while "slow" is still in flight.
                rig.exhaustPrimary()
                rig.messages(sessionId = "fast").also { it.bodyAsText() }
                rig.recoverPrimary()
                rig.releaseHold()

                assertTrue(slow.await().contains("event: message_stop"))
                assertEquals(listOf("primary-id", "backup-id"), rig.accountHeaders())
                assertEquals("backup", rig.poolView("fast").selectedLabel)
                assertEquals("quota usage reading full", rig.poolView("fast").lastSwitch?.reason)
                assertEquals("primary", rig.poolView("slow").selectedLabel)
                assertNull(rig.poolView("slow").lastSwitch)

                rig.messages(sessionId = "slow").also { it.bodyAsText() }

                assertEquals(listOf("primary-id", "backup-id", "primary-id"), rig.accountHeaders())
            } finally {
                rig.close()
            }
        }
}
