// NEW: Oct 10, 2026 — which of the person's messages Claude Code took back.
//
// When a Stop lands before any reply, Claude Code puts the message back into the prompt and writes nothing after it. The
// next message the person sends hangs off the SAME parent record, so the first message's branch is abandoned. A record
// names its parent (`parentUuid`), so a page can tell: a message is taken back when a later message of the person's
// shares its parent and no assistant record descends from it. An attachment between them is no answer, so the walk goes
// up through every record, not only through the person's and the assistant's.
//
// WHAT THIS DOES NOT CALL. The newest message with nothing after it is also what a turn that has not yet written its first
// output looks like, so the file alone cannot say; the caller combines the session's idle status with its newest row.
// And a pair split across two pages is judged on the page that holds both.
package splice.client.transcript

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

private const val UUID = "uuid"
private const val PARENT = "parentUuid"

/** The `type` of a transcript record an assistant wrote. */
internal const val ASSISTANT_TYPE = "assistant"

// why: a chain longer than this is a loop in a damaged file, not a conversation.
private const val MAX_CHAIN = 100_000

internal class TranscriptLineage {
    private val parents = HashMap<String, String?>()
    private val answers = mutableListOf<String>()
    private val persons = mutableListOf<Person>()

    private data class Person(val uuid: String, val parent: String, val index: Long)

    /** Every record of the page, whatever it is: the chain of parents runs through all of them. */
    fun note(record: JsonObject) {
        val uuid = JsonScalars.str(record, UUID) ?: return
        parents[uuid] = JsonScalars.str(record, PARENT)
        if (JsonScalars.str(record, "type") == ASSISTANT_TYPE) answers += uuid
    }

    /** A message of the person's, numbered [index] on the page. */
    fun person(record: JsonObject, index: Long) {
        val uuid = JsonScalars.str(record, UUID) ?: return
        val parent = JsonScalars.str(record, PARENT) ?: return
        persons += Person(uuid, parent, index)
    }

    /** The indices of the person's messages that were taken back. */
    fun takenBack(): Set<Long> {
        val answered = answeredRecords()
        val taken = HashSet<Long>()
        persons.forEachIndexed { at, person ->
            val laterSibling = persons.drop(at + 1).any { it.parent == person.parent && it.uuid != person.uuid }
            if (laterSibling && person.uuid !in answered) taken += person.index
        }
        return taken
    }

    /** Every record some assistant record descends from, itself included. */
    private fun answeredRecords(): Set<String> {
        val answered = HashSet<String>()
        for (answer in answers) {
            var at: String? = answer
            var steps = 0
            while (at != null && answered.add(at)) {
                at = parents[at]
                if (++steps > MAX_CHAIN) break
            }
        }
        return answered
    }
}
