// NEW: red-green proofs for the rule file that decides which tracked paths a text-scanning law reads.
package splice.lawsuite

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CandidateRulesTest {
    private val rules = CandidateRules("# c\ndir build\next PNG\nprefix .dev/research/\n")

    @Test
    fun `a path no rule rejects is read`() {
        assertTrue(rules.accepts("tools/gate/src/commands/hook.ts"))
        assertTrue(rules.accepts("README"))
    }

    @Test
    fun `a rejected directory, extension (any case) or prefix is not read`() {
        assertFalse(rules.accepts("core/build/out.txt"))
        assertFalse(rules.accepts("docs/logo.Png"))
        assertFalse(rules.accepts(".dev/research/capture.md"))
    }

    @Test
    fun `RED a line that is no rule fails by number, and an incomplete file fails`() {
        val thrown = assertThrows(IllegalStateException::class.java) { CandidateRules("dir a\nskip b\n") }
        assertTrue(thrown.message!!.contains("line 2"), thrown.message)
        assertThrows(IllegalStateException::class.java) { CandidateRules("dir a\n") }
        assertEquals(true, rules.accepts("a.txt"))
    }
}
