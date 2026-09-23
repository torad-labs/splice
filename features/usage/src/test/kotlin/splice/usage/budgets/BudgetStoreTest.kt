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

    @Test
    fun `each bad row is refused by name, and nothing is written`(@TempDir tmp: Path) {
        val file = tmp.resolve("budgets.json")
        val store = BudgetStore(file)
        val cases = listOf(
            listOf(Budget(head = " ", action = BudgetActions.WARN)) to "a budget needs a head",
            listOf(Budget(head = "codex", action = "yell")) to
                "codex: action must be one of [warn, block], was 'yell'",
            listOf(Budget(head = "codex", dailyUsd = -1.0, action = BudgetActions.WARN)) to
                "codex: daily_usd must not be negative",
            listOf(
                Budget(head = "codex", action = BudgetActions.WARN),
                Budget(head = "codex", action = BudgetActions.BLOCK),
            ) to "duplicate head(s) in one write: [codex]",
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
