// NEW: a kept state releases a snapshot once a newer cell replaces it. The state holds no field of the persisted
// state it was built from, so the first copy of a record is not pinned for the state's whole life.
package splice.provider.codex.state

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecordSnapshot
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

class CodeModeKeptStateRetentionTest {
    @Test
    fun `a snapshot a newer cell replaces is released while the kept state lives`() {
        val (kept, replaced) = replacedByNewerCell()
        awaitUntil("the snapshot a newer cell replaced is no longer reachable") {
            System.gc()
            replaced.get() == null
        }
        // The store holds the kept state for its whole life, so it stays reachable through the check.
        Reference.reachabilityFence(kept)
    }

    /** Builds a kept state over one record and writes a newer cell over it. Returns the kept state, which the caller
     *  holds as the store does, and only a weak reference to the first snapshot: nothing else keeps it. */
    private fun replacedByNewerCell(): Pair<CodeModeKeptState, WeakReference<CodeModeRecordSnapshot>> {
        val first = record("r1", CodeModePhase.ACTIVE)
        val replaced = WeakReference(first)
        val kept = CodeModeKeptState(1, CodeModePersistedState(1, listOf(first), emptyList()))
        kept.put(listOf(record("r1", CodeModePhase.COMPLETED)))
        assertTrue(kept.records.getValue("r1").phase == CodeModePhase.COMPLETED)
        return kept to replaced
    }

    /** Polls [done] with a deadline, never a sleep for a duration (kt-tests-no-wall-clock). */
    private fun awaitUntil(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            check(System.nanoTime() < deadline) { "never happened within 10 s: $what" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))
        }
    }

    private fun record(id: String, phase: CodeModePhase): CodeModeRecordSnapshot = CodeModeRecordSnapshot(
        id = id,
        key = "conversation-1",
        outer = JsonObject(mapOf("payload" to JsonPrimitive(id))),
        outerCallId = "call-$id",
        source = "1",
        phase = phase,
        pending = emptyList(),
        results = emptyMap(),
        output = null,
        error = null,
        totalCalls = 0,
        rounds = 0,
        updatedAt = 0L,
        lastDigest = "digest-$id",
    )
}
