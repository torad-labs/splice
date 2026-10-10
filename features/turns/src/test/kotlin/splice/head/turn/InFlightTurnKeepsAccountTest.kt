package splice.head.turn

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Spec section 11: switching is between turns, never inside one. */
class InFlightTurnKeepsAccountTest(@param:TempDir private val root: Path) {
    @Test
    fun `a turn in flight when its account fills finishes on that account and the next turn switches`() =
        runBlocking {
            val rig = AccountTurnRig(root)
            try {
                rig.start()
                rig.holdUpstream()
                var filled = false
                val held = rig.streamed(scenario = "hold") { line ->
                    // The first delta is on the wire, so the turn is in flight upstream: fill the window now.
                    if (!filled && line.contains("held")) {
                        filled = true
                        rig.exhaustPrimary()
                        rig.releaseHold()
                    }
                }

                assertTrue(filled, "the turn never reached its held delta: $held")
                assertTrue(held.contains("event: message_stop"), "the in-flight turn must finish: $held")
                assertEquals(listOf("primary-id"), rig.accountHeaders(), "no second request went out for that turn")

                val next = rig.messages().also { it.bodyAsText() }

                assertEquals(HttpStatusCode.OK, next.status)
                assertEquals(listOf("primary-id", "backup-id"), rig.accountHeaders())
                assertEquals("backup", rig.poolView().selectedLabel)
            } finally {
                rig.close()
            }
        }
}
