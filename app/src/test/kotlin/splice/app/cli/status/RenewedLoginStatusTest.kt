// What `splice status` prints for a head whose login was renewed after the provider refused the old one. claudex read
// "out of quota until Nov 12" for five days of served turns (Nov 5 to 10, 2026): the refusal of a token that was
// replaced hours later sat in a hold file, was restored at every boot, and no send could answer it again.
package splice.app.cli.status

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.ManagedHead
import splice.app.control.healthFor
import splice.app.control.readinessFor
import splice.app.probe.PlaygroundHeadFixture
import splice.app.probe.PlaygroundProviders
import splice.app.probe.UpstreamPlaygroundProbe
import splice.core.head.Head
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepth
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.core.util.LocalTimeText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import splice.daemonclient.DaemonProbe
import splice.diagnostics.playground.PlaygroundHead
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.ZoneId

/** codex's spent-week answer as its reader reads it, six days from its reset: the shape of the Nov 5 refusal. */
private const val WEEK_SPENT_BODY =
    """{"error":{"type":"usage_limit_reached","message":"Subscription quota exhausted","resets_in_seconds":518400}}"""

@OptIn(ExperimentalCoroutinesApi::class)
class RenewedLoginStatusTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var bin: Path

    private val topology = Topology(
        providers = mapOf(
            "codex" to ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://example.invalid",
                auth = AuthConfig(kind = "api-key", env = "TEST_CODEX_KEY"),
            ),
        ),
        heads = mapOf(
            "claudex" to HeadConfig(
                provider = "codex",
                port = 3099,
                discoveryPrefix = "claude-codex--",
                pinnedModel = "m",
                claude = ClaudeWrapperConfig(command = "claudex"),
            ),
        ),
    )

    /** The assembled head reads running, as the daemon's /health asks: the fixture never binds the head's port. */
    private fun running(managed: ManagedHead): ManagedHead = managed.copy(
        head = object : Head by managed.head {
            override fun healthSnapshot() = managed.head.healthSnapshot().copy(running = true)
        },
    )

    /** The claudex row `splice status` prints from this head's own /health, in Chicago time. */
    private fun row(head: ManagedHead): String {
        val heads = mapOf("claudex" to running(head))
        val now = System.currentTimeMillis()
        val body = healthFor(heads, readinessFor(heads, configuredHeads = 1)).json(now)
        val readings = StatusReadings(quotaResetAtEpochSeconds = DaemonProbe.parseHealth(body).quota.refusedUntil)
        val wrapper = bin.resolve("claudex")
        if (!Files.exists(wrapper, LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(wrapper, bin.resolve("target"))
        val vars = mapOf("SPLICE_BIN_DIR" to bin.toString(), "TEST_CODEX_KEY" to "synthetic-key")
        val chicago = LocalTimeText(ZoneId.of("America/Chicago"))
        val table = StatusTable(CliPalette(ColorDepth.NONE), WallClock { now }, chicago)
        return table.lines(topology, EnvReader(vars::get), readings)[1]
    }

    /** One send of the head's own login, answered with the spent week. */
    private suspend fun refused(head: Pair<ManagedHead, PlaygroundProviders>) {
        HttpClient(MockEngine { respond(WEEK_SPENT_BODY, HttpStatusCode.TooManyRequests) }).use { client ->
            UpstreamPlaygroundProbe(head.second, client)
                .run(PlaygroundHead("claudex", head.first.auth), "synthetic prompt", null)
        }
    }

    /** The access token the login file holds, replaced as a renewal replaces it. */
    private fun renewLogin() {
        val file = root.resolve("absent-auth.json")
        val login = Json.parseToJsonElement(Files.readString(file)).jsonObject
        val tokens = login.getValue("tokens").jsonObject.toMutableMap()
        tokens["access_token"] = JsonPrimitive(tokens.getValue("access_token").jsonPrimitive.content + "-renewed")
        SecureFile.writeAtomic0600(file, JsonObject(login + ("tokens" to JsonObject(tokens))).toString())
    }

    private fun build(scope: CoroutineScope) = PlaygroundHeadFixture(root).build(scope)

    @Test
    fun `a head whose refused login was renewed reads ready, and its new login's own refusal still reads`() = runTest {
        val first = build(backgroundScope)
        refused(first)
        val spent = row(first.first)
        assertTrue(spent.contains("out of quota until"), "its own login is refused: $spent")
        first.first.head.stop()

        renewLogin()
        val renewed = build(backgroundScope)
        try {
            val line = row(renewed.first)
            assertFalse(line.contains("out of quota"), "the refused token is gone, the head is not out of quota: $line")
            assertTrue(line.trimEnd().endsWith("ready"), line)

            refused(renewed)
            val again = row(renewed.first)
            assertTrue(again.contains("out of quota until"), "the renewed login's own refusal reads: $again")
        } finally {
            renewed.first.head.stop()
        }
    }
}
