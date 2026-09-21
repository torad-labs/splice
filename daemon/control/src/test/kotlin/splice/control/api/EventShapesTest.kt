// NEW: V4-126 — the event-shape pin, and its denominator is the point.
//
// The wall the row asked for is not "these six events have these fields" written out by hand: a
// hand-written list checks itself and can never fail for the event somebody forgets to add to it.
// The denominator here is ConsoleEvent's SEALED SUBCLASSES, read from the source, so a seventh
// event family fails this test by NAME until it is dispositioned. The expected map below is the
// other half of the same idea: each name is disposed with its exact wire shape, so a field added,
// removed or renamed on an existing family is red too.
//
// The shape is read from the SERIALIZER DESCRIPTOR rather than from a parsed sample, which is what
// makes it a contract pin: the descriptor is what the encoder actually writes, so this cannot drift
// from the wire the way an example JSON literal can.
package splice.control.api

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Wire name -> the element names that name's payload must carry, in order. The names are the
 *  console's own vocabulary (.dev/campaigns/web-console/FEATURES.md §6, splice-design owns it) — dotted, not
 *  underscored, which is the one place this implementation differed from the contract and adopted
 *  it rather than asking the console to adapt. */
private val EXPECTED_SHAPES: Map<String, List<String>> = mapOf(
    "head.state" to listOf("seq", "head", "state"),
    "turn.start" to listOf("seq", "head", "session"),
    "turn.end" to listOf("seq", "head", "perfRowId", "outcome"),
    "session.change" to listOf("seq", "session", "head"),
    "message.edge" to listOf("seq", "from", "to", "at"),
    "account.switch" to listOf("seq", "head", "from", "to"),
)

class EventShapesTest {

    @OptIn(InternalSerializationApi::class)
    @Test
    fun `every event family is dispositioned, and no undisposed family can appear`() {
        val actual = ConsoleEvent::class.sealedSubclasses.associate { subclass ->
            val descriptor = subclass.serializer().descriptor
            descriptor.serialName to descriptor.elementNames.toList()
        }
        // Set equality, both directions: a MISSING disposition fails, and so does a family in the
        // source that the map has never heard of. That second half is the whole point — it is the
        // one that fires when someone adds an event and forgets the console contract.
        assertEquals(EXPECTED_SHAPES.keys, actual.keys, "undeclared or undispositioned event families")
        EXPECTED_SHAPES.forEach { (name, shape) ->
            assertEquals(shape, actual[name], "the wire shape of $name moved")
        }
    }

    /** One instance per family, so the kind/name agreement below is checked on real payloads rather
     *  than on a list of strings that could itself be wrong. */
    private val samples: List<ConsoleEvent> = listOf(
        ConsoleEvent.HeadState(1, "claude", "running"),
        ConsoleEvent.TurnStart(2, "claude", "session-1"),
        ConsoleEvent.TurnEnd(3, "claude", "row-1", "ok"),
        ConsoleEvent.SessionChange(4, "session-1", "claude"),
        ConsoleEvent.EdgeEvent(5, "a", "b", 1_700_000_000L),
        ConsoleEvent.AccountSwitched(6, "claude", "primary", "backup"),
    )

    @OptIn(InternalSerializationApi::class)
    @Test
    fun `each declared kind equals the name its own serializer writes`() {
        // The `kind` property is a literal on each family because main code may not reflect over the
        // sealed subclasses to read the descriptor. This is what keeps that literal honest: a drifted
        // kind is red here rather than a stream the console silently stops switching on.
        samples.forEach { event ->
            assertEquals(
                event::class.serializer().descriptor.serialName,
                event.kind,
                "the kind on the class must equal its @SerialName",
            )
        }
        assertEquals(EXPECTED_SHAPES.keys, samples.map { it.kind }.toSet(), "one sample per family")
    }

    @Test
    fun `every event id is monotonic, so Last-Event-ID resume cannot replay or skip`() {
        val bus = EventBus()
        val first = bus.publish { seq -> ConsoleEvent.HeadState(seq, "claude", "running") }.seq
        val second = bus.publish { seq -> ConsoleEvent.HeadState(seq, "claude", "stopped") }.seq
        val third = bus.publish { seq -> ConsoleEvent.HeadState(seq, "claude", "running") }.seq
        assertEquals(listOf(first + 1, first + 2), listOf(second, third), "seq must advance by one")
    }
}
