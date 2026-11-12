package splice.head.turn

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Spec section 11: the first turn after a switch is accounted as cache-cold, and its perf row names the account. */
class SwitchTurnPerfRowTest(@param:TempDir private val root: Path) {
    @Test
    fun `the first turn after a switch writes a cache-cold perf row on the new account and the next one does not`() =
        runTest {
            val rig = AccountTurnRig(root)
            try {
                rig.start()
                rig.messages().also { it.bodyAsText() }
                rig.exhaustPrimary()
                rig.messages().also { it.bodyAsText() }
                rig.messages().also { it.bodyAsText() }

                val rows = rig.perfRows()
                val accounts = rows.map { it.getValue("account").jsonPrimitive.content }
                assertEquals(listOf("primary", "backup", "backup"), accounts)
                assertEquals(listOf(false, true, false), rows.map { it.getValue("cache_cold").jsonPrimitive.boolean })
            } finally {
                rig.close()
            }
        }
}
