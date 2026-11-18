// NEW: what a capability's text does on a dialect's head (docs/specs/harness.md, decision 5), as one set of checks
// each dialect's test runs on its own provider: real heads, real requests, and the bodies the provider got.
package splice.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.ProviderTuning

/** The checks for the provider [dialect] builds, on [rig]. */
public class CapabilityDialectChecks(
    private val rig: CapabilityRig,
    private val dialect: (ProviderTuning) -> Provider,
) {
    /** The provider gets the text after what the person typed, in the conversation's opening user turn, and the
     *  same opening on the next turn, with the worker asked once. */
    public fun textFollowsWhatWasTypedAndStays(): Unit = runBlocking {
        rig.reply = { result(FOUND) }
        val head = rig.head(listOf(rig.capability("brief")), dialect = dialect)
        val before = rig.provider.size

        rig.send(head, Turn("placed", opening(TYPED)))
        rig.send(head, Turn("placed", later(TYPED, "and test it")))

        val (first, next) = rig.provider.drop(before).map(::openingUserTurn)
        assertTrue(first.indexOf(TYPED) in 0 until first.indexOf(FOUND), "the text after what was typed: $first")
        assertEquals(first, next, "the same opening on the next turn")
        assertEquals(1, rig.asked.count { it.session == "placed" }, "the worker asked once")
    }

    /** A head the capability is not on for sends what it sends today, and calls nothing. */
    public fun aHeadItIsNotOnForSendsTodaysBytes(): Unit = runBlocking {
        val today = rig.head(emptyList(), dialect = dialect)
        val off = rig.head(listOf(rig.capability("brief").copy(heads = listOf("another-head"))), dialect = dialect)
        val before = rig.provider.size

        rig.send(today, Turn("off", opening(TYPED)))
        rig.send(off, Turn("off", opening(TYPED)))

        val (expected, sent) = rig.provider.drop(before)
        assertEquals(expected, sent, "byte for byte")
        assertEquals(0, rig.asked.count { it.session == "off" }, "the worker is not asked")
    }

    /** A dialect that cannot place the text leaves the body alone, and the turn's perf row says so. */
    public fun aDialectThatCannotPlaceLeavesTheBodyAlone(): Unit = runBlocking {
        rig.reply = { result(FOUND) }
        val today = rig.head(emptyList(), dialect = dialect)
        val unplacing = rig.head(listOf(rig.capability("brief"))) { tuning -> Unplacing(dialect(tuning)) }
        val before = rig.provider.size

        rig.send(today, Turn("unplaced", opening(TYPED)))
        rig.send(unplacing, Turn("unplaced", opening(TYPED)))

        val (expected, sent) = rig.provider.drop(before)
        assertEquals(expected, sent, "the body as it would go today")
        rig.awaitUntil("the turn's row is written") { rig.perfRows(unplacing).isNotEmpty() }
        val row = rig.perfRows(unplacing).single()
        assertEquals("true", row["capability_unplaced"]?.jsonPrimitive?.content, "the row says so: $row")
    }

    /** [delegate] on a dialect that has no place for a capability's text: the seam's default. */
    private class Unplacing(private val delegate: Provider) : Provider by delegate {
        override fun withFirstUserText(turn: BuiltTurn, text: String): BuiltTurn = turn
    }
}

private const val TYPED = "Add a --json flag to the status command."
private const val FOUND = "src/status.ts:10-40 holds the status command."
