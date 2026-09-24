// NEW: v0.4.0 review — a Host refusal is said in the daemon log, once per name, and a page that loops
// through names cannot turn that into a flood.
package splice.core.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForeignHostLogTest {

    private val lines = mutableListOf<String>()
    private val foreignHosts = ForeignHostLog("the control plane") { lines += it }

    @Test
    fun `a refused Host is logged once however often it returns`() {
        repeat(3) { foreignHosts.refused("attacker.example:3096") }

        assertEquals(1, lines.size, lines.toString())
        assertTrue(lines.single().contains("'attacker.example:3096'") && lines.single().contains("the control plane"))
    }

    @Test
    fun `past the cap the names go unnamed, said once`() {
        repeat(40) { foreignHosts.refused("n$it.attacker.example") }

        assertEquals(17, lines.size, "16 named, then one line for the rest")
        assertTrue(lines.last().contains("without a line each"), lines.last())
    }

    @Test
    fun `a Host cannot forge a second log line`() {
        foreignHosts.refused("evil.example\n[auth] forged")

        assertEquals(1, lines.size)
        assertFalse(lines.single().trimEnd('\n').contains('\n'), lines.single())
    }
}
