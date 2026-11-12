// NEW: v0.4.0 review — a Host refusal is said in the daemon log, once per name, and a page that loops
// through names cannot turn that into a flood.
package splice.core.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val MAX = 16

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

        assertEquals(17, lines.count { !it.contains("requests past") }, "16 named, then one line for the rest")
        assertTrue(lines[16].contains("without a line each"), lines[16])
    }

    // v0.4.0 review round 2: past the cap the log went quiet for the daemon's lifetime, so 17 refusals and
    // 17 million read the same. The rest are counted, and the count is said each time it doubles — about
    // log2(n) lines, however long a page loops.
    @Test
    fun `past the cap the refusals are counted, said at each doubling`() {
        repeat(MAX + 1000) { foreignHosts.refused("n$it.attacker.example") }

        val counts = lines.drop(MAX + 1)
        val said = counts.map { Regex("refused (\\d+) requests past").find(it)?.groupValues?.get(1)?.toLong() }
        assertEquals(listOf(2L, 4L, 8L, 16L, 32L, 64L, 128L, 256L, 512L), said, counts.toString())
        assertTrue(counts.last().contains("again at 1024"), counts.last())
    }

    @Test
    fun `a Host cannot forge a second log line`() {
        foreignHosts.refused("evil.example\n[auth] forged")

        assertEquals(1, lines.size)
        assertFalse(lines.single().trimEnd('\n').contains('\n'), lines.single())
    }
}
