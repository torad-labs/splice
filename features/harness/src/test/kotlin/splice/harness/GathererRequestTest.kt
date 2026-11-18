// NEW: a Context Getter's own requests (docs/specs/harness.md, decisions 9 and 10). They name the session they work for
// in splice's header: a head calls no capability for one, even one that meets every rule, and its record names that
// session, so the gather and the turn it was for can be joined.
package splice.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.capability.CapabilityMode
import splice.core.capability.CapabilityRule
import splice.core.turn.GATHERS_FOR_HEADER
import java.nio.file.Path

class GathererRequestTest(@param:TempDir private val tmp: Path) {
    private val rig = CapabilityRig(tmp)

    @AfterEach
    fun close() = rig.close()

    // mode any: the gatherer's print-mode request meets the rule, so only the header keeps it out
    private fun anyMode() = rig.capability("brief").let {
        it.copy(call = it.call.copy(rule = CapabilityRule(mode = CapabilityMode.ANY)))
    }

    @Test
    fun `a gatherer's first turn calls no capability, goes upstream as sent, and its row names its session`() =
        runBlocking {
            val head = rig.head(listOf(anyMode()))
            val gather = Turn("gatherer", opening("Explore"), userAgent = PRINT_AGENT)

            rig.send(head, gather, headers = mapOf(GATHERS_FOR_HEADER to "s1"))

            assertEquals(emptyList<Asked>(), rig.asked, "the worker was never posted")
            assertEquals(listOf("Explore"), firstUserTexts(rig.provider.single()), "the provider got it as it was sent")
            rig.awaitUntil("its row is written") { rig.perfRows(head).isNotEmpty() }
            val row = rig.perfRows(head).single()
            assertEquals("s1", row["worked_for"]?.jsonPrimitive?.content)
            assertEquals(JsonNull, row["held_on"], "it was not held")
        }

    @Test
    fun `the same first turn without the header is called, and its row names no session it worked for`() = runBlocking {
        val head = rig.head(listOf(anyMode()))

        rig.send(head, Turn("s1", opening("Explore"), userAgent = PRINT_AGENT))

        assertEquals(listOf("brief"), rig.asked.map { it.capability })
        rig.awaitUntil("its row is written") { rig.perfRows(head).isNotEmpty() }
        assertNull(rig.perfRows(head).single()["worked_for"])
    }
}
