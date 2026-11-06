package splice.accounts.pool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock

class AccountNameSnapshotsTest {
    @Test
    fun `bootstrap and expired names are unavailable while actual roster aliases share their exact ref`() {
        var now = 1000L
        val snapshots = AccountNameSnapshots(WallClock { now })
        assertNull(snapshots.current())
        snapshots.publish(listOf(row("First")))
        val held = requireNotNull(snapshots.current())
        assertEquals(AccountNameAlias("actual-ref", "First"), held.find("one", "pool-a"))
        assertEquals(AccountNameAlias("actual-ref", "First"), held.find("one", "actual-ref"))
        assertNull(held.find("other", "pool-a"))
        now += 60_001L
        assertNull(snapshots.current())
        val before = PerfWorkBudget.retainedBytes()
        snapshots.publish(listOf(row("Second")))
        assertEquals("Second", snapshots.current()?.find("one", "pool-a")?.label)
        assertEquals("First", held.find("one", "pool-a")?.label)
        assertTrue(PerfWorkBudget.retainedBytes() > before)
        PerfWorkBudget.release(held)
        snapshots.current()?.let(PerfWorkBudget::release)
    }

    @Test
    fun `oversized roster refuses the whole projection without retaining partial aliases`() {
        val snapshots = AccountNameSnapshots()
        val before = PerfWorkBudget.retainedBytes()
        snapshots.publish(List(65) { row("Name") })
        assertNull(snapshots.current())
        assertEquals(before, PerfWorkBudget.retainedBytes())
    }

    private fun row(name: String) = Json.parseToJsonElement(
        """{"ref":"actual-ref","display_name":"$name","heads":["one"],"account_labels":{"one":"pool-a"}}""",
    ).jsonObject
}
