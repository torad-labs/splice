// Through /api/perf/summary and /api/perf/turns, the two reads Usage and Requests make on load, against a perf file as
// big as a busy week writes, with recorded logins behind real login files. Desk c, Oct 12: a per-row read of the login
// files under the perf file lock kept a week of rows from ever loading, and no test with three rows could see it.
// Synthetic rows only, written here; no real log is copied.
package splice.app.control

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.claude.ClaudeCarrying
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.accounts.claude.ClaudeLoginPlaces
import splice.accounts.signin.LoginStatus
import splice.app.auth.claude.ClaudeCarryingPlaces
import splice.app.auth.claude.ClaudeCredentialProfiles
import splice.app.auth.claude.ClaudeLoginRead
import splice.app.sources.PerfRowsFileSource
import splice.core.auth.CredentialKey
import splice.core.config.StatePaths
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadWarn
import splice.usage.UsageHeads
import splice.usage.perf.PerfPayloads
import splice.usage.perf.PerfRoutes
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

private const val NOW = 1_800_000_000_000L
private const val WEEK_MS = 7L * 24 * 3_600_000
private const val ROWS_PER_HEAD = 40_000
private const val ANSWER_BOUND_MS = 15_000L
private const val GIVE_UP_MS = 60_000L
private val CLAUDE_HEADS = listOf("claude-a", "claude-b")
private val PEOPLE = listOf("ann", "bo", "cy")

class WeekOfRequestsRoutesTest {
    private val asked = AtomicInteger()

    @Test
    fun `a busy week answers Usage and Requests in seconds and reads each recorded login's files once per read`(
        @TempDir dir: Path,
    ) {
        val paths = StatePaths(baseOverride = dir.resolve("state")).also { Files.createDirectories(it.stateDir) }
        plantLogins(paths)
        val heads = (CLAUDE_HEADS + "grok").map { head(dir, it) }
        val places = CountingPlaces(
            ClaudeCarryingPlaces(emptyList(), ClaudeLoginRead(paths, {}, WallClock { NOW })),
        )
        val routes = PerfRoutes(UsageHeadLookup { name -> heads.filter { it.key == name } }, WallClock { NOW })
            .apply { accounts = RosterAccountNames(roster()) { places } }
        val payloads = PerfPayloads(UsageHeads { heads }, WallClock { NOW })
        val bodies = ArrayList<String>()
        val millis = HashMap<String, Long>()
        testApplication {
            application {
                routing {
                    get("/api/perf/turns") { routes.turns(call) }
                    get("/api/perf/summary") { payloads.summary(call) }
                }
            }
            val reads = CLAUDE_HEADS.map { "/api/perf/turns?head=$it&since=${NOW - WEEK_MS}&n=50" } +
                "/api/perf/summary?window=7d"
            reads.forEach { path ->
                millis[path] = measureTimeMillis {
                    bodies += withTimeout(GIVE_UP_MS) { client.get(path).bodyAsText() }
                }
            }
        }

        millis.forEach { (path, took) ->
            assertTrue(took < ANSWER_BOUND_MS, "$path took $took ms for a week of rows; Usage and Requests wait on it")
        }
        assertAnswers(bodies)
    }

    private fun assertAnswers(bodies: List<String>) {
        val summary = Json.parseToJsonElement(bodies.last()).jsonObject.getValue("heads").jsonArray
        val counted = summary.map { it.jsonObject }.first { it.getValue("key").jsonPrimitive.content == "claude-a" }
        assertEquals(
            ROWS_PER_HEAD.toLong(),
            counted.getValue("count").jsonPrimitive.content.toLong(),
            "the week's summary counts every row of the head",
        )
        val names = PEOPLE.map { it.replaceFirstChar(Char::uppercase) }.toSet()
        CLAUDE_HEADS.indices.forEach { i ->
            val head = Json.parseToJsonElement(bodies[i]).jsonObject.getValue("heads").jsonArray.single().jsonObject
            val labels = head.getValue("usage").jsonObject.getValue("accounts").jsonArray
                .map { it.jsonObject.getValue("label").jsonPrimitive.content }.toSet()
            assertEquals(names, labels, "each login reads its name")
        }
        assertFalse(bodies.any { "@example.invalid" in it }, "no page shows an email")
        assertEquals(
            CLAUDE_HEADS.size * PEOPLE.size,
            asked.get(),
            "the login files are read once per distinct recorded login on each head's read, never once per row",
        )
    }

    /** Three logins on each Claude head, each a real login folder: a credential file and the profile splice proved. */
    private fun plantLogins(paths: StatePaths) {
        val profiles = ClaudeCredentialProfiles(paths.stateDir, {})
        CLAUDE_HEADS.forEach { head ->
            PEOPLE.forEach { person ->
                val folder = Files.createDirectories(paths.stateDir.resolve("claude-accounts/$head/$person"))
                val token = "synthetic-$head-$person"
                Files.writeString(folder.resolve(".credentials.json"), """{"claudeAiOauth":{"accessToken":"$token"}}""")
                val key = CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token"))!!
                profiles.observed(key, ClaudeAccountIdentity("uuid-$head-$person", email(person)))
            }
        }
    }

    private fun email(person: String) = "$person@example.invalid"

    /** The roster as Accounts shows it: each login by ref and name, which holds no email a row was written with. */
    private fun roster() = AccountRoster {
        val rows = CLAUDE_HEADS.flatMap { head ->
            PEOPLE.map { person ->
                val ref = ClaudeAccountIdentity("uuid-$head-$person", email(person)).ref
                """{"ref":"$ref","display_name":"${person.replaceFirstChar(Char::uppercase)}","heads":["$head"],""" +
                    """"account_labels":{"$head":"$person"}}"""
            }
        }
        """{"accounts":[${rows.joinToString(",")}]}"""
    }

    /** A head whose perf file holds a week of synthetic rows, a request every ~15 seconds, each login in turn. */
    private fun head(dir: Path, key: String): UsageHead {
        val file = dir.resolve("$key-perf.jsonl")
        val every = WEEK_MS / ROWS_PER_HEAD
        Files.writeString(
            file,
            (0 until ROWS_PER_HEAD).joinToString("\n", postfix = "\n") { i ->
                val account = if (key in CLAUDE_HEADS) ""","account":"${email(PEOPLE[i % PEOPLE.size])}"""" else ""
                """{"ts":${NOW - WEEK_MS + 1 + i * every},"model":"m","outcome":"ok","total":${100 + i % 50}$account}"""
            },
        )
        return UsageHead(
            key = key,
            label = key,
            usage = HeadUsageSource { UsageView(0, 0, null) },
            warn = UsageHeadWarn(warnPct = 80, warnTokens5h = 0),
            sinks = UsageHeadSinks(perfRows = PerfRowsFileSource(file)),
        )
    }

    /** The real login owner, counting every ask it is given for a recorded login's name. */
    private inner class CountingPlaces(private val real: ClaudeCarrying) :
        ClaudeLoginPlaces,
        ClaudeCarrying by real {
        override fun places(): List<ClaudeLoginPlaceView> = emptyList()
        override suspend fun login(place: ClaudeLoginPlaceId, label: String?): LoginStatus = error("no login here")
        override suspend fun refresh(place: ClaudeLoginPlaceId): ClaudeLoginPlaceView = error("no refresh here")
        override fun poll(id: String): LoginStatus? = null
        override suspend fun submit(id: String, code: String): Boolean = false
        override fun accountLabel(head: String, account: String): String {
            asked.incrementAndGet()
            return real.accountLabel(head, account)
        }
    }
}
