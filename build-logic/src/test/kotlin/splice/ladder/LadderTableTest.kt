// The reading of a ladder row (LadderTable). The defect it guards: a row of the wrong shape surfaced as a ClassCastException on a line of the
// plugin script; now it is refused by task name and field.
package splice.ladder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LadderTableTest {
    private fun row(vararg extra: Pair<String, Any?>): Map<String, Any?> =
        mapOf("task" to "leg", "command" to listOf("bun", "x"), "why" to "because") + extra

    @Test
    fun `a complete row reads with its optional fields absent`() {
        val leg = LadderTable.parse(row())
        assertEquals(listOf("bun", "x"), leg.command)
        assertEquals(emptyList<String>(), leg.dependsOn)
        assertEquals(emptyList<String>(), leg.files.inputs)
    }

    @Test
    fun `a command that is not a list is refused by task and field`() {
        val failure = assertThrows(IllegalStateException::class.java) { LadderTable.parse(row("command" to "bun x")) }
        assertTrue(failure.message.orEmpty().contains("leg: `command` is not a list"), failure.message)
    }

    @Test
    fun `a non-string entry in a list is refused`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            LadderTable.parse(row("dependsOn" to listOf(":a", 3)))
        }
        assertTrue(failure.message.orEmpty().contains("`dependsOn` holds a non-string entry"), failure.message)
    }
}
