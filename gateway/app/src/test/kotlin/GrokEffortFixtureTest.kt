// NEW: GrokEffortVocabulary must stay equal to the dialect GrokEffortFixture single source.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.dialect.responses.GrokEffortFixture
import splice.provider.grok.GrokEffortVocabulary

class GrokEffortFixtureTest {

    @Test
    fun `GrokEffortVocabulary matches the dialect grok fixture tables`() {
        val grok = GrokEffortVocabulary()
        val fixture = GrokEffortFixture()
        for (token in listOf(
            "max", "ultra", "ultracode", "extra_high", "extra-high", "extrahigh", "xhigh",
            "high", "heavy", "extended", "medium", "standard", "normal",
            "low", "minimal", "none", "off", "fast", "light", "nope",
        )) {
            assertEquals(fixture.normalize(token), grok.normalize(token), token)
        }
        for (budget in listOf(0L, 1_000L, 2_000L, 10_000L, 64_000L)) {
            assertEquals(fixture.fromBudget(budget), grok.fromBudget(budget), budget.toString())
        }
        assertEquals(fixture.floor(null), grok.floor(null))
        assertEquals(false, grok.omitWhenDisabled())
        assertEquals("low", grok.floor("max"), "max is not a grok rung — floor to low")
        assertEquals("xhigh", grok.floor("xhigh"), "xhigh stays xhigh")
    }
}
