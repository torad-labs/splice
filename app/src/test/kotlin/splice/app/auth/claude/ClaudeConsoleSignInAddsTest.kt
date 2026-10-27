// Marcos, Oct 27, 2026: "SPlice console login ADDs an account, it doesn't replace it", after "Why the fuck does the
// signin refuses to start with sessions running". The goal, as the person at the console sees it: Sign in and Sign in
// again on a Claude Code login card, like + Add account, end with one more account the command can use. The login the
// command's running sessions use stays exactly as it was and stays the selected one, nothing refuses because sessions
// run, an account the command already holds answers Already added, and an attempt leaves no credential behind once it
// ends, however it ends.
//
// Everything runs as the daemon wires it (ClaudeLoginWiring, the control server's own routes) on a temporary home. Two
// things stand in for what cannot run here: `claude auth login`, a script that never reaches Anthropic and signs in the
// account its plan names, and the provider's profile call, which proves which account a token belongs to.
@file:OptIn(ExperimentalCoroutinesApi::class)

package splice.app.auth.claude

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.signin.LoginStatus
import splice.app.control.ControlAuth
import splice.app.control.ControlServer
import splice.app.control.controlServerFor
import splice.client.ClaudeHead
import splice.client.ClaudeLogins
import splice.client.HeadSessions
import splice.client.wrap.ClaudeToRun
import splice.client.wrap.WrapStateRead
import splice.core.auth.CredentialKey
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.process.LaunchOwners
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.minutes

private const val HEAD = "claude-splice"
private const val HEAD_PORT = 3104
private const val WAIT_MS = 20_000L
private const val POLL_MS = 20L
private val ENDED = setOf("signed_in", "live_after_restart", "failed")

// The stand-in for `claude auth login --claudeai`. It prints the browser address Claude Code prints, then does what the
// plan file beside it says: "ok <account>" signs that account in at once, as the browser finishing does; "paste" signs in
// the account whose code is pasted; "fail <account>" writes a sign-in and exits as a failed login does; "wait <account>"
// writes a sign-in and never finishes. It writes where Claude Code writes: CLAUDE_CONFIG_DIR, else $HOME/.claude.
private val FAKE_CLAUDE = """
    #!/bin/sh
    set -- ¤(cat "¤(dirname "¤0")/plan")
    step="¤1"; account="¤2"
    dir="¤{CLAUDE_CONFIG_DIR:-¤HOME/.claude}"
    echo "Opening your browser to sign in."
    echo "https://claude.com/cai/oauth/authorize?code=true&fixture=1"
    if [ "¤step" = paste ]; then IFS= read -r account || exit 1; fi
    mkdir -p "¤dir"
    printf '{"claudeAiOauth":{"accessToken":"synthetic.%s.%s","refreshToken":"r","expiresAt":4000000000000}}' "¤account" "¤¤" > "¤dir/.credentials.json"
    printf '{"oauthAccount":{"accountUuid":"%s","emailAddress":"%s@example.invalid"}}' "¤account" "¤account" > "¤dir/.claude.json"
    if [ "¤step" = fail ]; then exit 1; fi
    if [ "¤step" = wait ]; then exec sleep 120; fi
    exit 0
""".trimIndent().replace('¤', '$') + "\n"

private val TOPOLOGY = """
    [providers.native]
    dialect = "anthropic-passthrough"
    base_url = "http://127.0.0.1:9"
    [providers.native.auth]
    kind = "client"
    [heads.$HEAD]
    provider = "native"
    port = $HEAD_PORT
    discovery_prefix = "synthetic--"
""".trimIndent() + "\n"

class ClaudeConsoleSignInAddsTest {
    @TempDir
    lateinit var root: Path

    private lateinit var rig: Rig

    @BeforeEach
    fun up() {
        rig = Rig(root)
    }

    @AfterEach
    fun down() {
        rig.close()
    }

    @Test
    fun `Sign in on a Claude Code login card adds an account while sessions on the head, unknown and direct run`() {
        rig.sessions("head", "unknown", "direct")

        rig.plan("paste")
        val first = rig.signIn("claude-splice", code = "uuid-first")
        rig.plan("ok uuid-second")
        val second = rig.signIn("claude")

        assertEquals("signed_in", first.state, "the claude-splice card's Sign in: $first")
        assertEquals("signed_in", second.state, "the claude card's Sign in again: $second")
        assertEquals(setOf("uuid-first", "uuid-second"), rig.accountUuids(), "both are the command's accounts now")
    }

    @Test
    fun `the login running sessions use is untouched byte for byte, and the login it has selected stays selected`() {
        rig.login(rig.spliceDir, rig.spliceDir.resolve(".claude.json"), "uuid-live")
        rig.login(rig.nativeDir, rig.home.resolve(".claude.json"), "uuid-own")
        val store = ClaudeLogins(rig.paths.stateDir.resolve("claude-logins"))
        val seeded = store.login(ClaudeHead(HEAD, rig.spliceDir), "work", HeadSessions.Read(emptyList()))
        assertEquals("work", store.selected(), "the store selects the live login it saved: $seeded")
        val before = rig.live()

        rig.plan("ok uuid-new")
        val added = rig.signIn("claude-splice")

        assertEquals("signed_in", added.state, added.toString())
        assertEquals(before, rig.live(), "neither command's live login changed")
        assertEquals("work", store.selected(), "the selected login is still the one sessions use")
        assertEquals(setOf("uuid-new"), rig.accountUuids())
    }

    @Test
    fun `an account the command already holds answers Already added, and nothing changes`() {
        rig.plan("ok uuid-added")
        assertEquals("signed_in", rig.signIn("claude-splice").state)
        rig.login(rig.nativeDir, rig.home.resolve(".claude.json"), "uuid-own")
        val accounts = rig.accounts()
        val before = rig.live()

        rig.plan("ok uuid-added")
        val again = rig.signIn("claude-splice")
        rig.plan("ok uuid-own")
        val own = rig.signIn("claude-splice")

        assertEquals(Triple("failed", "already_added", accounts.single().label), again.outcome(), "$again")
        assertEquals(Triple("failed", "already_added", "claude"), own.outcome(), "the claude login holds it: $own")
        assertEquals(accounts.map { it.label to it.identity }, rig.accounts().map { it.label to it.identity })
        assertEquals(before, rig.live())
        assertEquals(emptyList<String>(), rig.pendingLeft())
    }

    @Test
    fun `a completed sign-in leaves no scratch folder and no credential behind`() {
        val before = rig.live()
        rig.plan("ok uuid-card")
        val card = rig.signIn("claude-splice")
        rig.plan("ok uuid-tile")
        val tile = rig.add()

        assertEquals(listOf("signed_in", "signed_in"), listOf(card.state, tile.state), "$card / $tile")
        assertEquals(emptyList<String>(), rig.pendingLeft())
        assertEquals(before, rig.live())
    }

    @Test
    fun `a failed sign-in leaves no scratch folder and no credential behind`() {
        val before = rig.live()
        rig.plan("fail uuid-gone")

        val card = rig.signIn("claude-splice")
        val tile = rig.add()

        assertEquals(listOf("not_completed", "not_completed"), listOf(card.kind, tile.kind), "$card / $tile")
        assertEquals(emptyList<String>(), rig.pendingLeft())
        assertEquals(before, rig.live())
        assertEquals(emptySet<String>(), rig.accountUuids())
    }

    @Test
    fun `an expired sign-in leaves no scratch folder and no credential behind`() {
        val before = rig.live()
        rig.plan("wait uuid-late")
        val card = rig.startCard("claude-splice")
        val tile = rig.startTile()
        rig.awaitCard(card) { it.state == "waiting" }
        rig.awaitTile(tile) { it.state == "waiting" }

        rig.scheduler.advanceTimeBy(10.minutes.inWholeMilliseconds + 1_000L)

        val cardEnd = rig.awaitCard(card) { it.state in ENDED }
        val tileEnd = rig.awaitTile(tile) { it.state in ENDED }
        assertEquals(listOf("expired", "expired"), listOf(cardEnd.kind, tileEnd.kind), "$cardEnd / $tileEnd")
        assertEquals(emptyList<String>(), rig.pendingLeft())
        assertEquals(before, rig.live())
    }

    @Test
    fun `a cancelled sign-in leaves no scratch folder and no credential behind`() {
        val before = rig.live()
        rig.plan("wait uuid-stopped")
        val card = rig.startCard("claude-splice")
        val tile = rig.startTile()
        rig.awaitCard(card) { it.state == "waiting" }
        rig.awaitTile(tile) { it.state == "waiting" }

        rig.stopDaemon()

        val cardEnd = rig.awaitCard(card) { it.state in ENDED }
        val tileEnd = rig.awaitTile(tile) { it.state in ENDED }
        assertEquals(listOf("cancelled", "cancelled"), listOf(cardEnd.kind, tileEnd.kind), "$cardEnd / $tileEnd")
        assertEquals(emptyList<String>(), rig.pendingLeft())
        assertEquals(before, rig.live())
    }

    @Test
    fun `Cancel then Sign in again on the same card starts a new sign-in instead of refusing`() {
        rig.plan("wait uuid-walked-away")
        val walkedAway = rig.startCard("claude-splice")
        rig.awaitCard(walkedAway) { it.state == "waiting" }

        rig.plan("ok uuid-again")
        val again = rig.signIn("claude-splice")

        assertEquals("signed_in", again.state, again.toString())
        assertTrue("uuid-again" in rig.accountUuids(), rig.accountUuids().toString())
    }
}

/** One status a sign-in answered: through the route the console polls, or the add tile's port. */
private data class Seen(
    val id: String?,
    val state: String?,
    val kind: String?,
    val reason: String?,
    val label: String?,
) {
    fun outcome() = Triple(state, kind, label)

    companion object {
        fun of(body: String): Seen {
            val json = Json.parseToJsonElement(body).jsonObject
            val field = { name: String -> (json[name] as? JsonPrimitive)?.contentOrNull }
            val reason = field("failure_reason") ?: field("error")
            return Seen(field("id"), field("state"), field("failure_kind"), reason, field("label"))
        }

        fun of(status: LoginStatus) =
            Seen(status.id, status.state.wire, status.failureKind?.wire, status.failureReason, status.label)
    }
}

/** A daemon's Claude login machinery on a temporary home, wired as the daemon wires it, behind its own control server. */
private class Rig(root: Path) : AutoCloseable {
    val home: Path = root.resolve("home")
    val paths = StatePaths(baseOverride = home.resolve(".splice").resolve("state"))
    val spliceDir: Path = home.resolve(".claude-$HEAD")
    val nativeDir: Path = home.resolve(".claude")

    // The daemon's own scope runs on virtual time, so a sign-in's ten minutes can pass in a moment.
    val scheduler = TestCoroutineScheduler()
    private val daemon = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val probes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bin: Path = root.resolve("bin")
    private val arm: ClaudeLoginArm
    private val control: ControlServer
    private val key: String
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val running = mutableListOf<Process>()
    private val launched = mutableListOf<Path>()

    init {
        Files.createDirectories(bin)
        val claude = bin.resolve("claude")
        Files.writeString(claude, FAKE_CLAUDE)
        Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rwx------"))
        plan("ok uuid-unused")
        val topology = root.resolve("splice.toml")
        Files.writeString(topology, TOPOLOGY)
        val refresh = ClaudeIdentityRefresh(
            probes,
            Dispatchers.IO,
            ClaudeCredentialProfiles(paths.stateDir, {}),
            ClaudeProfileCall { token ->
                token.split('.').getOrNull(1)?.let { ClaudeAccountIdentity(it, "$it@example.invalid") }
            },
            {},
        )
        arm = ClaudeLoginWiring(
            paths,
            daemon,
            WrapStateRead { ClaudeToRun.Wrapped(claude.toString()) },
            {},
            identityRefresh = refresh,
        ).create(topology)
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        control = controlServerFor(0, emptyMap(), ConfigService(paths), ControlAuth(mgmt, log = {}))
        control.ports.claudeLogins = arm.owner
        runBlocking { control.start() }
    }

    fun plan(plan: String) {
        Files.writeString(bin.resolve("plan"), plan + "\n")
    }

    /** Sessions of Claude Code running as the registry lists them: on this head by its launch, launched by splice with
     *  no record of which head ("unknown"), and started outside splice ("direct"). */
    fun sessions(vararg routes: String) {
        val registry = home.resolve(".claude").resolve("sessions")
        Files.createDirectories(registry)
        for (route in routes) {
            val builder = ProcessBuilder("sleep", "120")
            builder.environment().remove("SPLICE")
            if (route == "unknown") builder.environment()["SPLICE"] = "1"
            val process = builder.start()
            running += process
            if (route == "head") {
                val launches = StatePaths().stateDir
                LaunchOwners(launches).write(process.pid(), HEAD, "http://127.0.0.1:$HEAD_PORT", "session", "other")
                launched.add(launches.resolve("launch-owners").resolve("${process.pid()}.json"))
            }
            Files.writeString(
                registry.resolve("${process.pid()}.json"),
                """{"pid":${process.pid()},"sessionId":"session-$route","status":"busy",""" +
                    """"updatedAt":${System.currentTimeMillis()}}""",
            )
        }
    }

    /** A Claude Code login already in [dir], proved to be [account] as the profile call would prove it. */
    fun login(dir: Path, accountFile: Path, account: String) {
        Files.createDirectories(dir)
        val token = "synthetic.$account.live"
        Files.writeString(
            dir.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"$token","refreshToken":"r","expiresAt":4000000000000}}""",
        )
        Files.writeString(
            accountFile,
            """{"oauthAccount":{"accountUuid":"$account","emailAddress":"$account@example.invalid"}}""",
        )
        val digest = requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token")))
        ClaudeCredentialProfiles(paths.stateDir, {}).observed(digest, ClaudeAccountIdentity(account, null))
    }

    /** Both commands' live login files, as they read now. */
    fun live(): Map<String, String?> = listOf(
        spliceDir.resolve(".credentials.json"),
        spliceDir.resolve(".claude.json"),
        nativeDir.resolve(".credentials.json"),
        home.resolve(".claude.json"),
    ).associate { it.toString() to if (Files.exists(it)) Files.readString(it) else null }

    fun accounts(): List<ClaudeAccount> = ClaudeAccountFolders(paths.stateDir).accounts(HEAD)

    fun accountUuids(): Set<String?> = accounts().map { it.identity?.uuid }.toSet()

    /** Whatever a sign-in left in its scratch folders: each attempt's own folder under the head, and what is in it. */
    fun pendingLeft(): List<String> {
        val head = paths.stateDir.resolve("claude-accounts-pending").resolve(HEAD)
        if (!Files.exists(head)) return emptyList()
        return Files.walk(head).use { found ->
            found.filter { it != head }.map { head.relativize(it).toString() }.toList()
        }
    }

    /** The card's Sign in, as the console runs it: start, paste [code] when given, and poll to its end. */
    fun signIn(place: String, code: String? = null): Seen {
        val started = startCard(place)
        if (started.state in ENDED) return started
        if (code != null) {
            val asked = awaitCard(started) { it.state != "starting" }
            if (asked.state == "waiting") post("/api/auth/$HEAD/login/${started.id}/code", """{"code":"$code"}""")
        }
        return awaitCard(started) { it.state in ENDED }
    }

    fun startCard(place: String): Seen = Seen.of(post("/api/claude-logins/$place/login", """{"label":null}"""))
        .also { require(it.id != null) { "the card's sign-in did not start: $it" } }

    fun awaitCard(started: Seen, done: (Seen) -> Boolean): Seen =
        if (started.state in ENDED) started else pump({ Seen.of(get("/api/auth/$HEAD/login/${started.id}")) }, done)

    /** + Add account: the port its route hands a Claude command's add to. */
    fun add(): Seen = startTile().let { started -> awaitTile(started) { it.state in ENDED } }

    fun startTile(): Seen = Seen.of(arm.accounts.signIn.start(HEAD, null))

    fun awaitTile(started: Seen, done: (Seen) -> Boolean): Seen =
        pump({ arm.accounts.signIn.poll(started.id!!)?.let(Seen::of) ?: started }, done)

    /** The daemon stopping: every sign-in it runs is cancelled. */
    fun stopDaemon() = daemon.cancel()

    /** Runs the daemon's coroutines until [done] holds for what [read] answers, with a deadline, never a sleep for a
     *  duration (kt-tests-no-wall-clock): the sign-in child is a real process, so its output and exit arrive when they
     *  arrive. */
    private fun pump(read: () -> Seen, done: (Seen) -> Boolean): Seen {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (true) {
            scheduler.runCurrent()
            val seen = read()
            if (done(seen)) return seen
            check(System.nanoTime() < deadline) { "the sign-in never got past $seen" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_MS))
        }
    }

    private fun get(path: String): String = runBlocking {
        client.get("http://127.0.0.1:${control.listeningPort}$path") { header("Authorization", "Bearer $key") }
            .bodyAsText()
    }

    private fun post(path: String, body: String): String = runBlocking {
        client.post("http://127.0.0.1:${control.listeningPort}$path") {
            header("Authorization", "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()
    }

    override fun close() {
        daemon.cancel()
        probes.cancel()
        running.forEach { it.destroyForcibly() }
        launched.forEach { Files.deleteIfExists(it) }
        control.stop()
        client.close()
    }
}
