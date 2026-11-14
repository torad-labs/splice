package splice.app.instrumentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import splice.core.util.PerfWorkBudget

/**
 * The work budget as a test found it, and whether the test gave back everything it took.
 *
 * why: the budget is one total for the whole JVM, and part of it falls on its own. Bytes kept on an owner that nobody
 * releases stay charged until the JVM collects that owner (the Cleaner in PerfWorkBudget), and every Accounts read
 * leaves one such owner behind, the account names it published (AccountNameSnapshots). On Nov 10, 2026 a probe over
 * :app:test named twelve test classes that end with 4 KiB to 40 KiB charged that way, and thirteen later tests during
 * which it fell. A test that compared the whole total with the one it began with failed whenever a collection ran
 * while it did: main's run for 96dd41b25 read "expected: <4096> but was: <0>" on a profile download that had
 * delivered every byte.
 *
 * So the two parts are asked separately. Open reservations are let go only by the code that took them, and must be
 * exactly what they were. Bytes kept on owners may also fall when another test's owner is collected, and must be no
 * more than they were.
 */
internal class WorkBudgetMark {
    private val before = PerfWorkBudget.snapshot().accounting

    fun assertGivenBack() {
        val now = PerfWorkBudget.snapshot().accounting
        assertEquals(
            before.unretainedReservationBytes,
            now.unretainedReservationBytes,
            "open reservations differ from the ones this test began with",
        )
        val kept = now.retainedTicketBytes - before.retainedTicketBytes
        assertTrue(kept <= 0, "$kept bytes this test kept on an owner are still charged")
    }
}
