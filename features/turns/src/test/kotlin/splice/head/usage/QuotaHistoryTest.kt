// NEW: a head's plan readings over time (see QuotaHistory). The history file gains a line when a login's reading
// moves and none when it repeats, names the login each line belongs to, and stops with a login that was removed.
package splice.head.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

class QuotaHistoryTest {

    @TempDir
    lateinit var dir: Path

    private val now = 1_794_800_000_000L
    private val historyFile: Path get() = dir.resolve("codex-quota-history.jsonl")

    private fun tracker(login: String, file: String = "codex-quota.json") = QuotaTracker(
        dir.resolve(file),
        WallClock { now },
        LogSink { },
        history = QuotaHistory(dir.resolve("codex-quota.json"), login, LogSink { }),
    )

    private fun week(used: Double, at: Long, resetsAt: Long = 1_795_226_037L) =
        QuotaSnapshot(sevenDay = QuotaWindow(used, resetsAt, 604_800L), plan = "pro", updatedAt = at)

    private fun lines(): List<JsonObject> =
        Files.readAllLines(historyFile).map { Json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.weekUsed(): Double =
        getValue("reading").jsonObject.getValue("seven_day").jsonObject.getValue("used_percent").jsonPrimitive.double

    private fun JsonObject.readAt(): Long = getValue("reading").jsonObject.getValue("updated_at").jsonPrimitive.long

    private fun JsonObject.login(): String = getValue("login").jsonPrimitive.content

    @Test
    fun `the history gains a line when the plan's reading moves and none when it repeats`() {
        val quota = tracker("primary")

        quota.record(week(88.0, now))
        quota.record(week(88.0, now + 60_000L))
        quota.record(week(93.0, now + 300_000L))

        val kept = lines()
        assertEquals(listOf(88.0, 93.0), kept.map { it.weekUsed() }, "one line per point the plan moved")
        assertEquals(listOf(now, now + 300_000L), kept.map { it.readAt() }, "each line keeps when it was read")
        assertEquals(listOf("primary", "primary"), kept.map { it.login() })
    }

    @Test
    fun `a week that starts over is a line of its own`() {
        val quota = tracker("primary")

        quota.record(week(95.0, now))
        quota.record(week(1.0, now + 300_000L, resetsAt = 1_795_422_855L))

        assertEquals(listOf(95.0, 1.0), lines().map { it.weekUsed() })
    }

    @Test
    fun `two logins of one head share its history, each line under its own login's name`() {
        val second = tracker("account-2", "accounts-account-2-quota.json")
        val sixth = tracker("account-6", "accounts-account-6-quota.json")

        second.record(week(52.0, now))
        sixth.record(week(14.0, now + 1_000L))
        second.record(week(52.0, now + 2_000L))
        sixth.record(week(15.0, now + 3_000L))

        assertEquals(
            listOf("account-2" to 52.0, "account-6" to 14.0, "account-6" to 15.0),
            lines().map { it.login() to it.weekUsed() },
        )
    }

    @Test
    fun `a login removed from its head adds nothing more to the history`() {
        val quota = tracker("primary")
        quota.record(week(40.0, now))
        quota.retire()

        quota.record(week(41.0, now + 300_000L))

        assertEquals(listOf(40.0), lines().map { it.weekUsed() })
    }
}
