package splice.head.turn

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Spec section 11: a 429 whose reset is later than a turn can wait takes that account out, and the next turn goes
 *  out on the next account. */
class LongRetryAfterSwitchTest(@param:TempDir private val root: Path) {
    @Test
    fun `a 429 with a Retry-After longer than a turn can wait sends the next turn out on the second account`() =
        runTest {
            val rig = AccountTurnRig(root)
            try {
                rig.start()
                // The upstream answers this turn "429, retry in 60 seconds" and the head waits at most 15.
                rig.messages(scenario = "quota429").also { it.bodyAsText() }
                val next = rig.messages().also { it.bodyAsText() }

                assertEquals(HttpStatusCode.OK, next.status)
                assertEquals(listOf("primary-id", "backup-id"), rig.accountHeaders())
                assertEquals("backup", rig.poolView().selectedLabel)
            } finally {
                rig.close()
            }
        }
}
