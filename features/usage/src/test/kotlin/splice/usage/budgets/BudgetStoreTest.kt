// NEW: V4-133 — BudgetStore: a whole-set PUT lands validated budgets, a bad row (blank head, an
// action outside warn/block, a negative daily_usd, a duplicate head) is refused with no write at
// all, a file that will not parse degrades GET to empty rather than replacing it, and a write backs
// up what it replaces.
package splice.usage.budgets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.util.DaemonLog
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions

class BudgetStoreTest {

    @Test
    fun `an empty store answers no budgets, and a replace round-trips a 0600 file`(@TempDir tmp: Path) {
        val file = tmp.resolve("budgets.json")
        val store = BudgetStore(file)
        assertEquals(emptyList<Budget>(), store.budgets())

        val saved = store.replace(listOf(Budget(head = "claude", dailyUsd = 5.0, action = BudgetActions.WARN)))
        assertEquals(saved, store.budgets())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun `a null daily_usd is unbudgeted, not zero, and survives a round trip`(@TempDir tmp: Path) {
        val store = BudgetStore(tmp.resolve("budgets.json"))
        val saved = store.replace(listOf(Budget(head = "codex", dailyUsd = null, action = BudgetActions.BLOCK)))
        assertEquals(null, saved.single().dailyUsd)
        assertEquals(null, store.budgets().single().dailyUsd)
    }

    // The console prints each reason under the row it refused, so it reads as UI words and never
    // repeats the head the row already names (Hitstop's #271 critique, relayed by console).
    @Test
    fun `each bad row is refused by name, and nothing is written`(@TempDir tmp: Path) {
        val file = tmp.resolve("budgets.json")
        val store = BudgetStore(file)
        val cases = listOf(
            listOf(Budget(head = " ", action = BudgetActions.WARN)) to "Every budget needs a head.",
            listOf(Budget(head = "codex", action = "yell")) to
                "The action must be warn or block, not 'yell'.",
            listOf(Budget(head = "codex", dailyUsd = -1.0, action = BudgetActions.WARN)) to
                "The daily cap can't be negative.",
            listOf(
                Budget(head = "codex", action = BudgetActions.WARN),
                Budget(head = "codex", action = BudgetActions.BLOCK),
            ) to "Each head can have only one budget (more than one for codex).",
        )
        for ((bad, reason) in cases) {
            val refused = assertThrows(BudgetRefusal::class.java) { store.replace(bad) }
            assertEquals(reason, refused.message)
        }
        assertTrue(Files.notExists(file), "a refused write writes nothing")
    }

    @Test
    fun `a file that does not parse degrades GET to empty, and a write recovers it`(@TempDir tmp: Path) {
        val file = tmp.resolve("budgets.json")
        Files.writeString(file, "{ not json")
        val store = BudgetStore(file)
        assertEquals(emptyList<Budget>(), store.budgets(), "GET must always answer something")

        val recovered = store.replace(listOf(Budget(head = "grok", action = BudgetActions.WARN)))
        assertEquals(recovered, store.budgets())
    }

    // V4-296: a budgets.json that does not parse turned every budget off, block and warn alike, and nothing
    // said so. The store is built as ConsoleWiring builds it, so the line goes where the daemon's log does.
    @Test
    fun `a file that does not parse is logged once per version and GET names it - V4-296`(@TempDir tmp: Path) {
        val file = tmp.resolve("budgets.json")
        val seen = mutableListOf<String>()
        DaemonLog.install { seen += it }
        try {
            val store = BudgetStore(file)
            store.replace(listOf(Budget(head = "claude", dailyUsd = 5.0, action = BudgetActions.BLOCK)))
            breakOneByte(file, 5_000)
            repeat(3) { assertEquals(emptyList<Budget>(), store.budgets(), "enforcement reads no budgets") }
            val get = BudgetRoutes({ store }, ConfigService(StatePaths(baseOverride = tmp.resolve("state")))).read()
            breakOneByte(file, 10_000)
            store.budgets()

            val lines = seen.filter { "could not be read" in it }
            assertEquals(2, lines.size, "one line for each version that does not parse: $seen")
            assertTrue(lines.all { "$file" in it && "every head runs with no budget" in it }, "$lines")
            assertTrue("\"unreadable\":\"$file could not be read" in get.body, get.body)
        } finally {
            DaemonLog.install {}
        }
    }

    /** Swaps the first `"` on disk for a byte JSON cannot read, stamped [aheadMs] later so it reads as a
     *  new version. */
    private fun breakOneByte(file: Path, aheadMs: Long) {
        val stamp = Files.getLastModifiedTime(file).toMillis()
        Files.writeString(file, Files.readString(file).replaceFirst("\"", "#"))
        Files.setLastModifiedTime(file, FileTime.fromMillis(stamp + aheadMs))
    }

    @Test
    fun `each write backs up the version it replaces, and a hand edit is read without a restart`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("budgets.json")
        val store = BudgetStore(file)
        store.replace(listOf(Budget(head = "claude", action = BudgetActions.WARN)))
        val before = Files.readString(file)
        store.replace(listOf(Budget(head = "claude", action = BudgetActions.BLOCK)))
        assertEquals(before, Files.readString(tmp.resolve("budgets.json.bak")))

        Files.writeString(file, Files.readString(file).replace("\"block\"", "\"warn\""))
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000))
        assertEquals(BudgetActions.WARN, store.budgets().single().action)
    }
}
