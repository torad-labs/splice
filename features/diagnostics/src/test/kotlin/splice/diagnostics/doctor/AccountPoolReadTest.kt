// NEW: the daemon's account projection as the CLI reads it, one cause per arm. The read returned null
// for no key, a refused key, nothing answering and a body of another shape alike, and doctor's row
// read "the daemon's /api/accounts could not be read (mgmt key?)": a Warn with no fix and a guess for a
// reason (Hitstop's #271 critique, relayed by console). The daemon here is a two-route HttpServer; the
// mgmt key sits in a temp state dir the env points at.
package splice.diagnostics.doctor

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.head.ProviderAnswer
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.diagnostics.doctor.accounts.AccountHealthChecks
import splice.diagnostics.doctor.accounts.AccountRosterProjection
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

private const val POOLED = """{"accounts":[{"heads":["codex"],"label":"work","selected":true,"credential_present":true}]}"""

class AccountPoolReadTest {

    @TempDir
    lateinit var tmp: Path

    private var daemon: HttpServer? = null

    @AfterEach
    fun stop() {
        daemon?.stop(0)
    }

    /** A daemon whose /api/accounts answers [status] with [body]; returns its port. */
    private fun daemonAnswering(status: Int, body: String): Int {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/auth") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/accounts") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/health") { exchange ->
            val bytes = """{"version":"test"}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        daemon = server
        return server.address.port
    }

    private fun env(withKey: Boolean, port: Int = 0): EnvReader {
        val state = Files.createDirectories(tmp.resolve("state"))
        if (withKey) Files.writeString(state.resolve("mgmt-key"), "test-mgmt-key")
        val env = mapOf(
            "XDG_CONFIG_HOME" to Files.createDirectories(tmp.resolve("config")).toString(),
            "SPLICE_STATE_DIR" to state.toString(),
            "SPLICE_CONTROL_PORT" to port.toString(),
        )
        return EnvReader { env[it] }
    }

    private fun unread(port: Int, withKey: Boolean = true): AccountPoolsRead.Unread =
        assertInstanceOf(AccountPoolsRead.Unread::class.java, JdkAccountPoolRead()(port, env(withKey, port)))

    @Test
    fun `each reason the projection went unread is its own sentence, with the remedy that fits`() {
        val noKey = unread(daemonAnswering(200, POOLED), withKey = false)
        assertTrue(noKey.reason.contains("no management key") && noKey.reason.contains("mgmt-key check"), noKey.reason)
        assertEquals(null, noKey.fix, "the mgmt-key check carries that fix")
        stop()

        val refused = unread(daemonAnswering(401, """{"error":"unauthorized"}"""))
        assertTrue(refused.reason.contains("refused this shell's management key (HTTP 401)"), refused.reason)
        assertEquals(null, refused.fix, "no restart of ours makes two state dirs agree")
        stop()

        val failing = unread(daemonAnswering(503, """{"error":"x"}"""))
        assertEquals("the daemon's /api/accounts answered HTTP 503", failing.reason)
        assertEquals("splice logs", failing.fix)
        stop()

        val otherShape = unread(daemonAnswering(200, "[]"))
        assertTrue(otherShape.reason.contains("not with the account projection"), otherShape.reason)
        assertEquals(FIX_RESTART, otherShape.fix)
        stop()

        val silent = unread(TestPorts.reserve())
        assertTrue(silent.reason.startsWith("the daemon's /api/accounts did not answer ("), silent.reason)
        assertEquals("splice logs", silent.fix)
    }

    @Test
    fun `a readable projection is the pools, keyed by head`() {
        val port = daemonAnswering(200, POOLED)
        val read = JdkAccountPoolRead()(port, env(withKey = true, port))
        assertTrue(read is AccountPoolsRead.Read && read.pools.keys == setOf("codex"), "$read")
    }

    @Test
    fun `doctor and status read the same native roster as Accounts`() {
        val port = daemonAnswering(200, nativeRoster)
        val read = assertInstanceOf(AccountPoolsRead.Read::class.java, JdkAccountPoolRead()(port, env(true, port)))
        val view = read.pools.getValue("claude-splice")
        assertEquals(listOf("native:claude", "native:claude-splice"), view.accounts.map { it.label })
        assertEquals("on claude's login (1 of 2 open)", AccountPoolText().summary(view))
    }

    @Test
    fun `Accounts carrying login and present credential override stale native pool reporting`() {
        val roster = """
            {"accounts":[
              {"heads":["claude-splice"],"selector_key":"native:claude","credential_present":true,
               "login_place":{"id":"claude","command":"claude"},"available":true,"carrying_request":true,
               "five_hour_used_percent":4.0,"five_hour_current":true,
               "seven_day_used_percent":1.0,"seven_day_current":true},
              {"heads":["claude-splice"],"selector_key":"native:claude-splice","credential_present":true,
               "login_place":{"id":"claude-splice","command":"claude-splice"},"available":false,
               "carrying_request":false,"five_hour_used_percent":22.0,"five_hour_current":false,
               "seven_day_used_percent":59.0,"seven_day_current":false,
               "refusal":"Access token expired. Sign in again on claude-splice in the console."}],
             "head_pools":{"claude-splice":{"account_pool":{"selected_label":"native:claude-splice",
               "accounts":[
                 {"label":"native:claude","available":true,"credential_present":true,
                  "five_hour_used_percent":15.0,"seven_day_used_percent":30.0},
                 {"label":"native:claude-splice","primary":true,"selected":true,"available":false,
                  "credential_present":false,"five_hour_used_percent":22.0,"seven_day_used_percent":59.0}]}}}}
        """.trimIndent()
        val port = daemonAnswering(200, roster)
        val read = JdkAccountPoolRead()(port, env(true, port)) as AccountPoolsRead.Read
        val view = read.pools.getValue("claude-splice")
        assertEquals("native:claude", view.selectedLabel, "the login that carried the request is current")
        val expired = view.accounts.single { it.label == "native:claude-splice" }
        assertTrue(expired.credentialPresent, "expired does not mean absent")
        assertEquals(4.0, view.selectedAccount()?.fiveHourUsedPercent)
        assertEquals(1.0, view.selectedAccount()?.sevenDayUsedPercent)
        val detail = AccountPoolText().summary(view)
        assertTrue(detail.contains("claude's login"), detail)
        assertTrue(detail.contains("5h 4%") && detail.contains("7d 1%"), detail)
        assertTrue(!detail.contains("native:") && !detail.contains("credential missing"), detail)
    }

    @Test
    fun `an expired native login with a present credential reads expired with one advice remedy`() {
        val roster = nativeRoster.replace("\"credential_present\":false", "\"credential_present\":true")
        val port = daemonAnswering(200, roster)
        val run = DoctorTestPorts.doctor().collect(env(true, port))
        val checks = run.sections.toMap().getValue("accounts")
        val expired = checks.single { it.detail.contains("claude-splice") && it.detail.contains("expired") }
        assertEquals(CheckStatus.WARN, expired.status)
        assertTrue(expired.detail.contains("cannot take over"), expired.detail)
        assertEquals("Sign in again on claude-splice in the console.", expired.fix)
        assertEquals(FixKind.ADVICE, expired.fixKind, "native login expiry is never a splice credential action")
        assertEquals(null, expired.fixId)
        assertTrue(checks.none { it.detail.contains("credential missing") }, checks.toString())
    }

    @Test
    fun `an unsafe selected roster row never silently selects the primary`() {
        val roster = """{"accounts":[{"heads":["codex"],"label":"primary","primary":true,"available":true},""" +
            """{"heads":["codex"],"label":"unsafe label","selected":true}]}"""
        val port = daemonAnswering(200, roster)
        val read = assertInstanceOf(AccountPoolsRead.Read::class.java, JdkAccountPoolRead()(port, env(true, port)))
        assertTrue(AccountPoolText().summary(read.pools.getValue("codex")).startsWith("selection unknown"))
    }

    @Test
    fun `doctor names the expired native place without suggesting a splice credential refresh`() {
        val port = daemonAnswering(200, nativeRoster)
        val run = DoctorTestPorts.doctor().collect(env(true, port))
        val checks = run.sections.toMap().getValue("accounts")
        val expired = checks.single { it.detail.contains("claude-splice") && it.detail.contains("expired") }
        assertEquals(CheckStatus.WARN, expired.status)
        assertTrue(expired.fix.orEmpty().contains("claude-splice"), expired.toString())
        assertTrue(!expired.fix.orEmpty().contains("splice login"), "native credentials have one writer")
    }

    @Test
    fun `a joined roster keeps each head's own label selection and availability`() {
        val roster = """{"accounts":[{"heads":["first","second"],"label":"one","selected":true,
            "available":true,"credential_present":true,"account_labels":{"first":"one","second":"two"}}],
            "head_pools":{"first":{"account_pool":{"selected_label":"one","accounts":[
            {"label":"one","selected":true,"available":true,"credential_present":true}]}},
            "second":{"account_pool":{"selected_label":"two","accounts":[
            {"label":"two","selected":true,"available":false,"credential_present":true}]}}}}"""
        val port = daemonAnswering(200, roster)
        val read = JdkAccountPoolRead()(port, env(true, port)) as AccountPoolsRead.Read
        assertEquals("one", read.pools.getValue("first").accounts.single().label)
        val second = read.pools.getValue("second")
        assertEquals(
            "two",
            second.accounts.single().label,
            "the first head's label never replaces this head's selector",
        )
        assertEquals("two", second.selectedLabel)
        assertEquals(false, second.accounts.single().available, "a shared credential does not share head-local holds")
        assertEquals(false, second.selectionUnknown)
    }

    @Test
    fun `Health names Kimi's newest 403 instead of certifying its stored login`() {
        val roster = """
            {"accounts":[
              {"heads":["claude-kimi"],"kind":"kimi-oauth","single_login":true,"primary":true,
               "credential_present":true,"last_refusal":{"status":403,"at_ms":1790000000000}},
              {"heads":["pooled"],"kind":"api-key","label":"work","selected":true,"available":true,
               "credential_present":true,"last_refusal":{"status":403,"at_ms":1790000000001}},
              {"heads":["pooled"],"kind":"api-key","label":"backup","available":true,
               "credential_present":true,"last_refusal":{"status":429,"at_ms":1790000000002}}]}
        """.trimIndent()
        val port = daemonAnswering(200, roster)
        val original = env(true, port)
        val environment = EnvReader { if (it == "SYNTHETIC_POOL_KEY") "synthetic" else original(it) }
        val credential = tmp.resolve("synthetic-kimi.json")
        Files.writeString(credential, """{"access_token":"synthetic"}""")
        plantRefusalTopology(credential)
        val run = DoctorTestPorts.doctor().collect(environment)
        val auth = run.sections.toMap().getValue("auth").single { it.name == "claude-kimi" }
        assertEquals(CheckStatus.WARN, auth.status, "the auth line doctor wrote for claude-kimi: ${auth.detail}")
        assertTrue(auth.detail.contains("HTTP 403"), auth.detail)
        assertTrue(
            !auth.detail.contains("newest upstream request"),
            "an account refusal is not the head's latest request",
        )
        assertTrue(!auth.detail.contains("signed in"), auth.detail)
        assertTrue(!auth.fix.orEmpty().contains("login"), "a resource refusal is not a credential rejection")
        val refusals = run.sections.flatMap { (section, rows) ->
            rows.filter { it.detail.contains("retained provider refusal") }.map { "$section/${it.name}" }
        }
        assertEquals(
            listOf("accounts/pooled account backup", "accounts/pooled account work", "auth/claude-kimi"),
            refusals.sorted(),
            "auth owns only the unkeyed head observation; each pooled account keeps its own refusal",
        )
        val json = DoctorTestPorts.doctor().reportJson(run, environment)
        assertTrue(json.contains("HTTP 403") && json.contains("claude-kimi"), json)
        val checks = (Json.parseToJsonElement(json).jsonObject.getValue("checks") as JsonArray)
            .filterIsInstance<JsonObject>()
        assertEquals(3, checks.count { JsonScalars.str(it, "detail").orEmpty().contains("retained provider refusal") })
        assertTrue(
            checks.none {
                JsonScalars.str(it, "id") == "accounts/claude-kimi" &&
                    JsonScalars.str(it, "detail").orEmpty().contains("HTTP 403")
            },
            "the duplicate head refusal leaves the accounts checks in the shipped JSON report",
        )
    }

    @Test
    fun `joined per-head selectors and native refusals never become an unkeyed head verdict`() {
        val roster = """
            {"accounts":[
              {"heads":["single","pooled"],"label":"work","account_labels":{"single":null,"pooled":"work"},
               "credential_present":true,"last_refusal":{"status":403,"at_ms":1790000000000}},
              {"heads":["native"],"label":"claude","selector_key":"native:claude",
               "login_place":{"id":"claude"},"credential_present":true,
               "last_refusal":{"status":429,"at_ms":1790000000001}},
              {"heads":["unsafe"],"label":"unsafe label","credential_present":true,
               "last_refusal":{"status":403,"at_ms":1790000000002}}]}
        """.trimIndent()
        val read = AccountRosterProjection().read(roster) as AccountPoolsRead.Read
        assertEquals(setOf("single"), read.lastRefusals.keys)
        assertEquals(setOf("work"), read.accountRefusals.getValue("pooled").keys)
        assertEquals(setOf("native:claude"), read.accountRefusals.getValue("native").keys)
        assertTrue("unsafe" !in read.accountRefusals, "an invalid selector cannot become printable account text")
        val retained = AccountHealthChecks.checks(read, read.lastRefusals)
        assertEquals(
            setOf("pooled account work", "native account native:claude"),
            retained.map { it.name }.toSet(),
        )
    }

    @Test
    fun `only the exact head refusal already rendered by auth is omitted from accounts`() {
        val answer = ProviderAnswer(403, 1_790_000_000_000L)
        val read = AccountPoolsRead.Read(emptyMap(), lastRefusals = mapOf("synthetic" to answer))
        assertEquals(1, AccountHealthChecks.checks(read).size, "missing auth keeps the refusal observable")
        assertEquals(0, AccountHealthChecks.checks(read, mapOf("synthetic" to answer)).size)
        val earlier = ProviderAnswer(403, answer.observedAtEpochMs - 1L)
        assertEquals(1, AccountHealthChecks.checks(read, mapOf("synthetic" to earlier)).size)
    }

    private fun plantRefusalTopology(credential: Path) {
        val config = Files.createDirectories(tmp.resolve("config/splice"))
        Files.writeString(
            config.resolve("splice.toml"),
            """
            [providers.kimi]
            dialect = "anthropic-passthrough"
            base_url = "https://synthetic.example"
            auth = { kind = "kimi-oauth", file = "$credential" }
            [[providers.kimi.models]]
            id = "synthetic-model"
            context_window = 4000
            [heads.claude-kimi]
            provider = "kimi"
            port = 3998
            discovery_prefix = "synthetic--"
            pinned_model = "synthetic-model"
            [providers.pool]
            dialect = "openai-responses"
            base_url = "https://synthetic.example"
            auth = { kind = "api-key", env = "SYNTHETIC_POOL_KEY" }
            [[providers.pool.models]]
            id = "synthetic-model"
            context_window = 4000
            [heads.pooled]
            provider = "pool"
            port = 3997
            discovery_prefix = "pool--"
            pinned_model = "synthetic-model"
            """.trimIndent(),
        )
    }

    private val nativeRoster = """
        {"accounts":[
          {"heads":["claude-splice"],"kind":"client","label":"claude","selector_key":"native:claude",
           "login_place":{"id":"claude","command":"claude"},"credential_present":true,"available":true,"selected":true},
          {"heads":["claude-splice"],"kind":"client","label":"claude-splice","selector_key":"native:claude-splice",
           "login_place":{"id":"claude-splice","command":"claude-splice"},"credential_present":false,"available":false,
           "refusal":"Access token expired. Sign in again on claude-splice in the console."}
        ]}
    """.trimIndent()

    /** RED before: the row was WARN "the daemon's /api/accounts could not be read (mgmt key?)" with no fix. */
    @Test
    fun `doctor's accounts row carries the cause and its fix, never a guess`() {
        val port = daemonAnswering(503, """{"error":"x"}""")
        val run = DoctorTestPorts.doctor().collect(env(withKey = true, port))
        val row = run.sections.toMap().getValue("accounts").single()
        assertEquals(CheckStatus.WARN, row.status)
        assertEquals("the daemon's /api/accounts answered HTTP 503", row.detail)
        assertEquals("splice logs", row.fix)
    }
}
