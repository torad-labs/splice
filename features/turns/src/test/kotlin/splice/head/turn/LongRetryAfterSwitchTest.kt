package splice.head.turn

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Spec section 11 and its Oct deviation: a 429 whose reset is later than a turn can wait takes that account out,
 *  and the same turn goes out on the next account before any byte reaches the client (0e65c98f1). */
class LongRetryAfterSwitchTest(@param:TempDir private val root: Path) {
    @Test
    fun `a 429 with a Retry-After longer than a turn can wait sends the same turn out on the second account`() =
        runTest {
            val rig = AccountTurnRig(root)
            try {
                rig.start()
                // The upstream answers the primary "429, retry in 60 seconds" and the head waits at most 15.
                val reply = rig.messages(scenario = "quota429primary").also { it.bodyAsText() }

                assertEquals(HttpStatusCode.OK, reply.status)
                assertEquals(listOf("primary-id", "backup-id"), rig.accountHeaders())
                assertEquals("backup", rig.poolView().selectedLabel)
            } finally {
                rig.close()
            }
        }
}
