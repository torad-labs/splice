// Native console quota and legacy attribution are read from the same truth as Accounts.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.accounts.claude.ClaudeLoginPlaces
import splice.accounts.signin.LoginStatus
import splice.app.auth.claude.ClaudeCarryingPlaces
import splice.app.auth.claude.ClaudeCredentialProfiles
import splice.app.auth.claude.ClaudeLoginLocation
import splice.app.auth.claude.ClaudeLoginRead
import splice.client.ClaudeHead
import splice.client.ClaudeLoginTarget
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.CredentialKey
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.topology.API_KEY_WIRE
import splice.core.topology.AuthKindRegistry
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindow
import splice.core.usage.QuotaWindowView
import splice.core.util.WallClock
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.usage.CredentialQuotaFiles
import splice.models.roster.DeclaredHead
import splice.models.roster.DeclaredHeads
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.sessions.registry.SessionStatus
import splice.usage.perf.PerfProjectionRead
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsProjection
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow
import splice.usage.perf.PerfTurnFacts
import splice.usage.perf.ProjectedPerfRowsSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Files
import java.nio.file.Path

private const val HEAD = "synthetic-native"
private const val TIMEOUT_MS = 30_000L

class NativeUsageTruthTest {
    @Test
    fun `native projected reads normalize attribution without full-window fallback or losing original references`() {
        val full = listOf("claude-code", "proved@example.invalid", null).mapIndexed { index, account ->
            PerfRow(
                ts = index.toLong() + 10,
                outcome = "ok",
                fields = mapOf("synthetic_metric" to 77L),
                facts = PerfTurnFacts(account = account),
            )
        }
        val facts = full.map { it.copy(fields = emptyMap()) }
        val evidence = PerfRowsWindow(
            facts,
            oldestHeldTs = 1,
            dropsBefore = 3,
            readError = "synthetic read error",
            skipped = 7,
            newestHeldTs = 12,
        )
        var completed = 0
        val source = object : PerfRowsSource, ProjectedPerfRowsSource {
            override fun window(sinceMs: Long): PerfRowsWindow = error("a native projected read must not fall back")
            override fun <T> projected(sinceMs: Long, read: PerfProjectionRead<T>): T {
                val projection = object : PerfRowsProjection {
                    override val window: PerfRowsWindow = evidence
                    override fun complete(rows: List<PerfRow>): List<PerfRow> {
                        completed++
                        assertEquals(1, rows.size, "only the selected display row is materialized")
                        assertSame(
                            facts.first(),
                            rows.single(),
                            "normalization must restore the original projected reference",
                        )
                        return listOf(full.first())
                    }
                }
                return read(projection)
            }
        }
        val native = NativeAccountRows(source)
        native.projected(0) { projection ->
            val read = projection.window
            val normalized = facts.map {
                if (it.facts.account == "claude-code") it.copy(facts = it.facts.copy(account = null)) else it
            }
            assertEquals(evidence.copy(rows = normalized), read)
            val row = projection.complete(listOf(read.rows.first())).single()
            assertNull(row.facts.account)
            assertEquals(77L, row.fields["synthetic_metric"])
        }
        assertEquals(1, completed)
    }

    @TempDir
    lateinit var home: Path

    private val now = System.currentTimeMillis()
    private val reset = now / 1000L + 3600
    private val paths by lazy { StatePaths(baseOverride = home.resolve("state")) }
    private var probes = 0

    private fun location(id: ClaudeLoginPlaceId, account: String?): ClaudeLoginLocation {
        val folder = Files.createDirectories(home.resolve(id.wire))
        Files.writeString(
            folder.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"synthetic-${id.wire}"}}""",
        )
        val record = folder.resolve(".claude.json")
        val identity = account?.let { """"accountUuid":"$it","emailAddress":"proved@example.invalid"""" } ?: ""
        Files.writeString(record, """{"oauthAccount":{$identity}}""")
        if (account != null) {
            val key = requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer synthetic-${id.wire}")))
            ClaudeCredentialProfiles(paths.stateDir, {}).observed(
                key,
                ClaudeAccountIdentity(account, "proved@example.invalid"),
            )
        }
        return ClaudeLoginLocation(id, ClaudeLoginTarget(ClaudeHead(HEAD, folder), record), home)
    }

    private fun observed(id: ClaudeLoginPlaceId, pct: Double, at: Long = now) {
        val key = CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer synthetic-${id.wire}"))!!
        CredentialQuotaFiles(paths.quotaFile(HEAD), {}).observed(
            key,
            QuotaSnapshot(
                fiveHour = QuotaWindow(pct, reset, 18_000),
                sevenDay = QuotaWindow(65.0, reset + 86400, 604_800),
                plan = "max",
                updatedAt = at,
            ),
        )
    }

    private fun native(
        locations: List<ClaudeLoginLocation>,
        carried: ClaudeCarryingPlaces? = null,
    ): ClaudeLoginPlaces = object : ClaudeLoginPlaces {
        override fun places(): List<ClaudeLoginPlaceView> =
            ClaudeLoginRead(paths, {}, WallClock { now }).places(locations)
        override suspend fun refresh(place: ClaudeLoginPlaceId): ClaudeLoginPlaceView =
            places().single { it.id == place }
        override suspend fun login(place: ClaudeLoginPlaceId, label: String?): LoginStatus =
            error("no login in this fixture")
        override fun poll(id: String): LoginStatus? = null
        override suspend fun submit(id: String, code: String): Boolean = false
        override fun carrying(head: String): ClaudeLoginPlaceId? = carried?.carrying(head)
        override fun carrying(head: String, session: String): ClaudeLoginPlaceId? = carried?.carrying(head, session)
    }

    private fun digest(token: String): String =
        requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token")))

    private fun managed(kind: String = "client"): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = HEAD
            override val label: String = "Synthetic"
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, 0, "synthetic")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(false, kind, emptyMap())
        },
        sources = HeadSources(
            usage = object : HeadUsageSource {
                override fun snapshot(): UsageView = UsageView(
                    7,
                    3,
                    null,
                    QuotaView(QuotaWindowView(99, reset, now / 1000), null, "wrong-head-plan"),
                )
                override suspend fun probeNow() { probes++ }
            },
            compact = object : HeadCompactSource {
                override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
            },
            logs = object : HeadLogSource {
                override fun tail(lines: Int): String = ""
                override fun path(): String = ""
            },
            perfRows = PerfRowsSource {
                PerfRowsWindow(
                    listOf(null, "claude-code", "proved@example.invalid").mapIndexed { i, account ->
                        PerfRow(
                            ts = now - i,
                            outcome = "ok",
                            fields = emptyMap(),
                            facts = PerfTurnFacts(account = account),
                        )
                    },
                    oldestHeldTs = now - 1000,
                    dropsBefore = 3,
                    newestHeldTs = now,
                )
            },
        ),
        usageWarning = UsageWarning(warnPct = 80, warnTokens5h = 0),
        authSurface = HeadAuthSurface(authKind = kind),
    )

    /** The head a case runs against: its auth kind, the family it is declared with, and the usage it answers. */
    private inner class SyntheticHead(
        private val kind: String = "client",
        private val family: String? = null,
        private val usageSource: HeadUsageSource? = null,
    ) {
        fun managed(): ManagedHead =
            managed(kind).let { head ->
                if (usageSource == null) head else head.copy(sources = head.sources.copy(usage = usageSource))
            }

        fun declared(): DeclaredHead = DeclaredHead("synthetic-provider", null, family)
    }

    private fun serve(
        port: ClaudeLoginPlaces?,
        head: SyntheticHead = SyntheticHead(),
        sessions: SessionSource? = null,
        check: suspend (suspend (String, Boolean) -> JsonObject) -> Unit,
    ) = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val key = MgmtKey(paths)
            val server = controlServerFor(
                port = 0,
                heads = mapOf(HEAD to head.managed()),
                config = ConfigService(paths),
                runtime = ControlRuntime(sessions = sessions),
                auth = ControlAuth(mgmtKey = key, log = {}),
            )
            // Late binding is intentional: UsageMount must not capture the construction-time null.
            server.ports.claudeLogins = port
            server.ports.declaredHeads = DeclaredHeads { mapOf(HEAD to head.declared()) }
            HttpClient(CIO).use { client ->
                try {
                    server.start()
                    check { path, probe ->
                        val url = "http://127.0.0.1:${server.listeningPort}$path"
                        val response = if (probe) {
                            client.post(url) { header("Authorization", "Bearer ${key.get()}") }
                        } else {
                            client.get(url) { header("Authorization", "Bearer ${key.get()}") }
                        }
                        val text = response.bodyAsText()
                        assertEquals(200, response.status.value, text)
                        Json.parseToJsonElement(text).jsonObject
                    }
                } finally {
                    server.stop()
                }
            }
        }
    }

    private fun usage(root: JsonObject): JsonObject =
        root.getValue("heads").jsonArray.single().jsonObject.getValue("usage").jsonObject

    @Test
    fun `every registered subscription kind and the late-bound local family classify rows without plan metadata`() {
        val noPlan = HeadUsageSource { UsageView(0, 0, null) }
        val kinds = AuthKindRegistry.knownKinds().map { it.wire to "unpriced_plan_requests" } +
            (API_KEY_WIRE to "unpriced_local_requests")
        kinds.forEach { (kind, cause) ->
            serve(null, SyntheticHead(kind, if (kind == API_KEY_WIRE) "local" else null, noPlan)) { read ->
                val block = read("/api/perf/turns?head=$HEAD&since=0", false)
                    .getValue("heads").jsonArray.single().jsonObject
                val totals = block.getValue("usage").jsonObject.getValue("totals").jsonObject
                assertEquals("3", totals.getValue(cause).jsonPrimitive.content, kind)
                assertEquals("0", totals.getValue("unpriced_undeclared_requests").jsonPrimitive.content, kind)
            }
        }
        serve(null, SyntheticHead(API_KEY_WIRE, "openai", noPlan)) { read ->
            val block = read("/api/perf/turns?head=$HEAD&since=0", false)
                .getValue("heads").jsonArray.single().jsonObject
            val totals = block.getValue("usage").jsonObject.getValue("totals").jsonObject
            assertEquals("3", totals.getValue("unpriced_undeclared_requests").jsonPrimitive.content)
            assertEquals("0", totals.getValue("unpriced_local_requests").jsonPrimitive.content)
            assertEquals("0", totals.getValue("unpriced_plan_requests").jsonPrimitive.content)
        }
    }

    @Test
    fun `Accounts and Usage read the same sibling-observed account snapshot and refused probes retain it`() {
        val places = listOf(
            location(ClaudeLoginPlaceId.NATIVE, "one-subscription"),
            location(ClaudeLoginPlaceId.SPLICE, "one-subscription"),
        )
        observed(ClaudeLoginPlaceId.NATIVE, 12.0)
        serve(native(places)) { read ->
            val accounts = read("/api/accounts", false).getValue("accounts").jsonArray.map { it.jsonObject }
            val before = usage(read("/api/usage", false))
            val five = before.getValue("quota").jsonObject.getValue("five_hour").jsonObject
            accounts.forEach {
                assertEquals(
                    it.getValue("five_hour_used_percent").jsonPrimitive.content.toDouble(),
                    five.getValue("used_pct").jsonPrimitive.content.toDouble(),
                )
            }
            assertEquals(reset.toString(), five.getValue("resets_at").jsonPrimitive.content)
            assertEquals("7", before.getValue("output_tokens_5h").jsonPrimitive.content)
            assertEquals("3", before.getValue("entries").jsonPrimitive.content)
            assertEquals(
                before,
                usage(read("/api/usage/probe", true)),
                "a probe that files no new reading retains the snapshot",
            )
            assertEquals(1, probes)
        }
    }

    /** Both commands send through one head, so its usage is the login its requests carry (2026-10-04: claude-splice
     *  sent the ~/.claude login at 91% while Models and Usage read the folder's stale 59%). The record starts empty,
     *  which is what a restart leaves: until a request matches a place, the head reads the command's own folder. */
    @Test
    fun `a client head's usage reads the login its newest request carried, not the command's own folder`() {
        val places = listOf(
            location(ClaudeLoginPlaceId.NATIVE, "native-subscription"),
            location(ClaudeLoginPlaceId.SPLICE, "folder-subscription"),
        )
        observed(ClaudeLoginPlaceId.NATIVE, 91.0)
        observed(ClaudeLoginPlaceId.SPLICE, 59.0)
        val carried = ClaudeCarryingPlaces(places, ClaudeLoginRead(paths, {}, WallClock { now }))
        serve(native(places, carried)) { read ->
            suspend fun headUsage(): JsonObject = usage(read("/api/usage", false))
            suspend fun fiveHour(): String = headUsage().getValue("quota").jsonObject.getValue("five_hour").jsonObject
                .getValue("used_pct").jsonPrimitive.content
            assertEquals("59", fiveHour(), "before any request the head reads its command's own folder")

            carried.sent(HEAD, null, digest("synthetic-${ClaudeLoginPlaceId.NATIVE.wire}"))
            assertEquals("91", fiveHour(), "a request carrying the ~/.claude login moves the head to that login")
            val warn = headUsage().getValue("warn").jsonObject
            assertEquals("quota_5h", warn.getValue("source").jsonPrimitive.content, "the near-limit warning: $warn")
            assertEquals("91", warn.getValue("pct").jsonPrimitive.content, "the near-limit warning: $warn")

            carried.sent(HEAD, null, digest("synthetic-unknown-login"))
            assertEquals("59", fiveHour(), "an unmatched send clears the stale native place before fallback")

            carried.sent(HEAD, null, digest("synthetic-${ClaudeLoginPlaceId.SPLICE.wire}"))
            assertEquals("59", fiveHour(), "the newest matched request decides, in either direction")
        }
    }

    /** The Accounts roster names the same login: true on the place whose credential carried the head's newest matched
     *  request, false on its sibling, and null on both until a request matched, which is what a restart leaves. */
    @Test
    fun `the Accounts roster marks the login carrying a head's requests, and neither before one matched`() {
        val places = listOf(
            location(ClaudeLoginPlaceId.NATIVE, "native-subscription"),
            location(ClaudeLoginPlaceId.SPLICE, "folder-subscription"),
        )
        val carried = ClaudeCarryingPlaces(places, ClaudeLoginRead(paths, {}, WallClock { now }))
        serve(native(places, carried)) { read ->
            suspend fun flags(): Map<String, String> = read("/api/accounts", false).getValue("accounts").jsonArray
                .map { it.jsonObject }
                .associate { row ->
                    row.getValue("label").jsonPrimitive.content to (row["carrying_request"]?.toString() ?: "absent")
                }
            assertEquals(mapOf("claude" to "null", "claude-splice" to "null"), flags(), "no request has matched yet")

            carried.sent(HEAD, null, digest("synthetic-${ClaudeLoginPlaceId.NATIVE.wire}"))
            val nativeCarries = mapOf("claude" to "true", "claude-splice" to "false")
            assertEquals(nativeCarries, flags(), "a request carrying the ~/.claude login marks that place")

            carried.sent(HEAD, null, digest("synthetic-unknown-login"))
            assertEquals(
                mapOf("claude" to "null", "claude-splice" to "null"),
                flags(),
                "a credential no place holds must not mark the previous place as carrying",
            )

            carried.sent(HEAD, null, digest("synthetic-${ClaudeLoginPlaceId.SPLICE.wire}"))
            assertEquals(mapOf("claude" to "false", "claude-splice" to "true"), flags(), "the newest match decides")
        }
    }

    @Test
    fun `different and unknown identities never inherit another native place's quota`() {
        val places = listOf(
            location(ClaudeLoginPlaceId.NATIVE, "different-subscription"),
            location(ClaudeLoginPlaceId.SPLICE, null),
        )
        observed(ClaudeLoginPlaceId.NATIVE, 12.0)
        serve(native(places)) { read -> assertFalse(usage(read("/api/usage", false)).containsKey("quota")) }
    }

    @Test
    fun `a stale native snapshot remains visible without becoming a current warning`() {
        val places = listOf(
            location(ClaudeLoginPlaceId.NATIVE, "one-subscription"),
            location(ClaudeLoginPlaceId.SPLICE, "one-subscription"),
        )
        observed(ClaudeLoginPlaceId.NATIVE, 12.0, now - 3_600_000)
        serve(native(places)) { read ->
            val value = usage(read("/api/usage", false))
            val five = value.getValue("quota").jsonObject.getValue("five_hour").jsonObject
            assertEquals("12", five.getValue("used_pct").jsonPrimitive.content)
            assertEquals("false", five.getValue("current").jsonPrimitive.content)
            assertEquals("none", value.getValue("warn").jsonObject.getValue("source").jsonPrimitive.content)
            assertEquals((now / 1000L - 3600).toString(), five.getValue("observed_at").jsonPrimitive.content)
            assertEquals(value, usage(read("/api/usage/probe", true)))
            val accounts = read("/api/accounts", false).getValue("accounts").jsonArray
            accounts.forEach {
                assertEquals("12.0", it.jsonObject.getValue("five_hour_used_percent").jsonPrimitive.content)
                assertEquals("false", it.jsonObject.getValue("five_hour_current").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `legacy native place labels have no account identity and their Requests filter matches the aggregate`() {
        serve(null) { read ->
            val block = read("/api/perf/turns?head=$HEAD&since=0&local=0", false)
                .getValue("heads").jsonArray.single().jsonObject
            val groups = block.getValue("usage").jsonObject.getValue("accounts").jsonArray.map { it.jsonObject }
            assertEquals(2, groups.size)
            assertEquals("2", groups.single { it["key"] == JsonNull }.getValue("requests").jsonPrimitive.content)
            assertEquals("1", groups.single { it["key"] != JsonNull }.getValue("requests").jsonPrimitive.content)
            val filtered = read("/api/perf/turns?head=$HEAD&since=0&local=0&unattributed=account", false)
                .getValue("heads").jsonArray.single().jsonObject
            assertEquals("2", filtered.getValue("count").jsonPrimitive.content)
            assertTrue(filtered.getValue("rows").jsonArray.all { it.jsonObject["account"] == JsonNull })
        }
    }

    @Test
    fun `native attribution changes only the unproved name and preserves all window evidence`() {
        val row = PerfRow(ts = 100, outcome = "ok", fields = emptyMap(), facts = PerfTurnFacts(account = "claude-code"))
        val proved = row.copy(ts = 101, facts = row.facts.copy(account = "proved@example.invalid"))
        val held = PerfRowsWindow(listOf(row, proved), 50, 7, "synthetic unread generation", 2, 101)
        val source = PerfRowsSource { since ->
            assertEquals(42L, since)
            held
        }
        val normalized = NativeAccountRows(source).window(42)
        assertEquals(held.copy(rows = listOf(row.copy(facts = row.facts.copy(account = null)), proved)), normalized)
        assertNull(normalized.rows.first().facts.account)
        assertSame(proved, normalized.rows.last(), "proved attribution remains borrowed, not rebuilt")
    }

    @Test
    fun `the same label on a nonnative provider stays an account label`() {
        serve(null, SyntheticHead(kind = "chatgpt-oauth")) { read ->
            val block = read("/api/perf/turns?head=$HEAD&since=0&local=0", false)
                .getValue("heads").jsonArray.single().jsonObject
            assertEquals(3, block.getValue("usage").jsonObject.getValue("accounts").jsonArray.size)
            val five = usage(read("/api/usage", false)).getValue("quota").jsonObject.getValue("five_hour").jsonObject
            assertEquals("99", five.getValue("used_pct").jsonPrimitive.content)
        }
    }

    /** What /api/sessions names as each session's login (2026-10-04: all 19 claude-splice sessions read "Login not
     *  reported" while 25 of their requests were filed under an account). Every session on a client head forwards its
     *  own login through the one head, so the head's newest match is another session's answer, never this one's. */
    @Nested
    inner class SessionAccounts {
        private val sessionIds = listOf("synthetic-session-a", "synthetic-session-b", "synthetic-session-c")

        private fun registry(ids: List<String>): SessionSource = object : SessionSource {
            override fun read(): List<SessionRecord> = ids.mapIndexed { pid, id ->
                SessionRecord(
                    sessionId = id,
                    name = null,
                    status = SessionStatus(),
                    route = SessionRoute.Head(HEAD),
                    availability = SessionAvailability.LIVE,
                    process = SessionProcess(
                        pid = pid + 1L,
                        cwd = null,
                        startedAt = null,
                        updatedAt = null,
                        messagingSocketPath = null,
                    ),
                    client = SessionClient(kind = null, version = null),
                )
            }

            override fun list(): SessionListing = SessionListing(read())
        }

        private suspend fun accounts(read: suspend (String, Boolean) -> JsonObject): Map<String, String?> =
            read("/api/sessions", false).getValue("sessions").jsonArray.map { it.jsonObject }.associate { row ->
                row.getValue("session_id").jsonPrimitive.content to row.getValue("account").jsonPrimitive.contentOrNull
            }

        @Test
        fun `each session on a client head reads the place its own requests carried, and null before one matched`() {
            val places = listOf(
                location(ClaudeLoginPlaceId.NATIVE, "native-subscription"),
                location(ClaudeLoginPlaceId.SPLICE, "folder-subscription"),
            )
            val carried = ClaudeCarryingPlaces(places, ClaudeLoginRead(paths, {}, WallClock { now }))
            val (first, second, idle) = sessionIds
            serve(native(places, carried), sessions = registry(sessionIds)) { read ->
                assertEquals(sessionIds.associateWith { null }, accounts(read), "no session has a matched request yet")

                carried.sent(HEAD, first, digest("synthetic-${ClaudeLoginPlaceId.NATIVE.wire}"))
                carried.sent(HEAD, second, digest("synthetic-${ClaudeLoginPlaceId.SPLICE.wire}"))
                val own = mapOf(first to "claude", second to "claude-splice", idle to null)
                assertEquals(own, accounts(read), "each session reads its own place, never the head's newest")

                carried.sent(HEAD, first, digest("synthetic-unknown-login"))
                carried.sent(HEAD, null, digest("synthetic-${ClaudeLoginPlaceId.NATIVE.wire}"))
                assertEquals(
                    own + (first to null),
                    accounts(read),
                    "an unknown credential clears only its session; an unnamed request restores no session",
                )

                carried.sent(HEAD, first, digest("synthetic-${ClaudeLoginPlaceId.SPLICE.wire}"))
                assertEquals(own + (first to "claude-splice"), accounts(read), "a session's newest match decides")
            }
        }

        /** The record is bounded by recency: past its bound the session that sent least recently is forgotten and reads
         *  null again, while a session that sent again since is kept. */
        @Test
        fun `the per-session record forgets the least recently sending session past its bound`() {
            val places = listOf(location(ClaudeLoginPlaceId.NATIVE, "native-subscription"))
            val carried = ClaudeCarryingPlaces(places, ClaudeLoginRead(paths, {}, WallClock { now }))
            val key = digest("synthetic-${ClaudeLoginPlaceId.NATIVE.wire}")
            val bound = 4096 // ClaudeCarryingPlaces' REMEMBERED_CARRYING_SESSIONS
            carried.sent(HEAD, "synthetic-refreshed", key)
            carried.sent(HEAD, "synthetic-oldest", key)
            repeat(bound - 2) { carried.sent(HEAD, "synthetic-filler-$it", key) }
            carried.sent(HEAD, "synthetic-refreshed", key)
            assertEquals(ClaudeLoginPlaceId.NATIVE, carried.carrying(HEAD, "synthetic-oldest"), "at the bound")

            carried.sent(HEAD, "synthetic-newest", key)
            assertNull(carried.carrying(HEAD, "synthetic-oldest"), "past the bound the least recent sender goes")
            assertEquals(ClaudeLoginPlaceId.NATIVE, carried.carrying(HEAD, "synthetic-refreshed"), "sent again, kept")
            assertEquals(ClaudeLoginPlaceId.NATIVE, carried.carrying(HEAD, "synthetic-newest"))
        }

        /** The label the Accounts roster's single-login row stands for, and the one that head's requests are filed
         *  under; the console names it from the head plus that label. */
        @Test
        fun `a single-login OAuth head's sessions read the login's stable label, never a sentence`() {
            serve(null, SyntheticHead(kind = "chatgpt-oauth"), sessions = registry(sessionIds.take(1))) { read ->
                assertEquals(mapOf(sessionIds.first() to "primary"), accounts(read))
            }
        }
    }
}
