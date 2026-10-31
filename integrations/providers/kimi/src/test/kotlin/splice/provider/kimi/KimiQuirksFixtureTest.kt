// NEW: the dialect test fixture and KimiQuirks must stay the same object so goldens cannot drift.
package splice.provider.kimi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.dialect.anthropic.KimiProfileFixture

class KimiQuirksFixtureTest {
    @Test
    fun `KimiQuirks matches the dialect kimi profile fixture`() {
        assertEquals(KimiProfileFixture().kimi("kimi"), KimiQuirks().kimi("kimi"))
    }
}
