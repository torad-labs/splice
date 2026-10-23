// NEW: LAN share, phase 2, milestone 6 (docs/specs/lan-share.md). The goal: a Claude Code session on one machine sees
// the live sessions of a machine it is joined with in ListAgents, as `<name>@<machine>`, and messages them with
// SendMessage, each way; a session that ends leaves the other machine at the next look; and only a joined machine is
// shown any. Two machines in one JVM, each with its own Claude Code registry, sockets folder and state: "torad" shares
// over its real TLS listener, and "mini" joined it and runs the daemon's session link, as the daemon runs both. The
// sessions are Claude Code's own shapes (ClaudeCodeSession); everything between them is splice's real code.
package splice.share.sessions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.core.topology.RemoteConfig
import splice.core.topology.ShareConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.sessions.mirror.MirrorCounts
import splice.sessions.mirror.MirrorPlace
import splice.sessions.mirror.SessionMirrors
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.share.ShareInfo
import splice.share.ShareListener
import splice.share.ShareSession
import splice.share.ShareStart
import splice.share.SharedCatalogs
import splice.share.SharedQuotas
import splice.share.remote.RemoteFound
import splice.share.remote.RemoteHttp
import splice.share.remote.RemoteJoin
import splice.share.remote.RemoteJoined
import splice.share.remote.RemoteLinks
import splice.share.remote.RemoteNames
import splice.share.remote.RemoteSessions
import splice.share.remote.RemoteTrust
import splice.share.remote.RemotesStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import javax.net.ssl.SSLSocket
import kotlin.random.Random

// why: how often the link looks in this test; the daemon's own cadence is what the goal's ten seconds rest on.
private const val LOOK_MS = 200L

// why: a look slow enough that the mirrors one offer makes last the whole test: three looks, capped at a minute.
private const val SLOW_LOOK_MS = 20_000L

// why: how often a test reads the registry while it waits for the next look.
private const val POLL_MS = 50L

// why: how long a test waits for what the next few looks bring before it fails.
private const val SETTLE_MS = 15_000L

// why: well past the half hour after which splice reads a session it has not heard from as stale.
private const val HOUR_MS = 3_600_000L

// why: the client name the joined machine gives, as `splice remote` gives its host name.
private const val MINI = "mini"

// why: the most mirrors one daemon hosts: every machine on the network may join, and each mirror is a process.
private const val MIRRORS_AT_MOST = 256

// why: a fixed seed, so a failing draw of session names is the same draw on the next run.
private const val NAMES_SEED = 23

// why: enough sessions that equal names, empty ones and long ones all come up in one draw.
private const val NAMES_DRAWN = 60

// why: a session's name as Claude Code's ListAgents can show it, its SendMessage takes it, and an envelope's
// from-name can carry it. Never `<name>@<machine>`: SendMessage refuses an @ as a teammate's team ("to must be a bare
// teammate name", Claude Code 2.1.309, Oct 23).
private val SAFE_MIRROR = Regex("^$MINI/[A-Za-z0-9._-]{1,64}$")

class SessionsAcrossMachinesTest {

    @TempDir
    lateinit var tmp: Path

    /** One machine: its Claude Code registry, its sockets folder (owner-only, with short names, so a socket path fits
     *  the 104 bytes a Mac allows), its state, and the mirrors its daemon hosts there. */
    private inner class Machine(letter: String) {
        val home: Path = Files.createDirectories(tmp.resolve(letter))
        val registry: Path = Files.createDirectories(home.resolve("r"))
        val sockets: Path = Files.createDirectory(
            home.resolve("s"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
        private val env = mapOf(
            "SPLICE_STATE_DIR" to home.resolve("state").toString(),
            "SPLICE_CONFIG" to home.resolve("splice.toml").toString(),
            "HOME" to home.toString(),
        )
        val paths = StatePaths(envReader = EnvReader { env[it] })
        val mirrors = SessionMirrors(MirrorPlace(registry, sockets, home.resolve("m.log")), scope, Dispatchers.IO) { }

        fun session(name: String, heardAt: Long? = null) =
            ClaudeCodeSession(registry, sockets, name, heardAt).also(opened::add)

        /** How splice's Sessions page here reads the session named [name] at [now]: null when it is not listed. */
        fun availability(name: String, now: Long): SessionAvailability? =
            SessionRegistry(registry, { SessionRoute.Unknown }, clock = { now }).read()
                .firstOrNull { it.name == name }?.availability
    }

    private val opened = mutableListOf<AutoCloseable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var torad: Machine
    private lateinit var mini: Machine
    private lateinit var listener: ShareListener
    private var port = 0

    /** What mini calls torad: its `[remotes]` key, from the name torad gives. */
    private lateinit var toradName: String

    @BeforeEach
    fun machines() {
        torad = Machine("t")
        mini = Machine("m")
        port = TestPorts.reserve()
        val paths = torad.paths
        listener = ShareListener(
            ShareStart(Topology(), paths, MgmtKey(paths), ConfigService(paths)) { },
            SharedCatalogs { null },
            SharedQuotas { null },
            paths,
            { },
            torad.mirrors,
        )
        runBlocking { listener.follow(ShareConfig(enabled = true, port = port)) }
    }

    @AfterEach
    fun stop() {
        scope.cancel()
        // A session closes twice when a test ended it: its close removes only what is still there.
        opened.asReversed().forEach(AutoCloseable::close)
        runBlocking { listener.stop() }
        torad.mirrors.close()
        mini.mirrors.close()
    }

    /** mini joins torad as `splice remote` does, keeps the token and the pin, and its daemon starts the link, which
     *  it answers. */
    private fun join(): Job = link(links(joined().second))

    /** mini's links once it kept [joined]'s token and pin, as `splice remote` keeps them. */
    private fun links(joined: RemoteJoined.Joined): RemoteLinks {
        val store = RemotesStore(mini.paths.stateDir.resolve("share").resolve("remotes.json"))
        store.save(toradName, joined.token)
        val remotes = mapOf(toradName to RemoteConfig(joined.http.base("127.0.0.1", port), joined.pin))
        return RemoteLinks(Topology(remotes = remotes), store)
    }

    /** mini's daemon starting its session link over [links]. */
    private fun link(links: RemoteLinks): Job =
        checkNotNull(RemoteSessions(links, mini.mirrors, { }, LOOK_MS).start(scope))

    /** mini offers torad its running sessions once, as a look does, with a look slow enough that torad keeps their
     *  mirrors through the test without another. */
    private fun offered(links: RemoteLinks) {
        val ours = mini.mirrors.local().map { ShareSession(it.id, it.name, it.heardAt) }
        links.api(toradName).offer(checkNotNull(links.url(toradName)), ours, SLOW_LOOK_MS)
    }

    /** A collection of mini's outbox that torad takes in and starts to hold, and whose connection then goes, as when
     *  mini sleeps, its daemon restarts or its Wi-Fi drops. */
    private fun cutCollection(info: ShareInfo, joined: RemoteJoined.Joined) {
        val before = heardFrom(MINI)
        val factory = RemoteTrust(info.fingerprint, "torad").context.socketFactory
        (factory.createSocket("127.0.0.1", port) as SSLSocket).use { socket ->
            val asked = "GET /share/v1/sessions/outbox?wait=25 HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                "Authorization: Bearer ${joined.token}\r\n\r\n"
            socket.outputStream.write(asked.toByteArray())
            socket.outputStream.flush()
            val heard = runBlocking {
                val until = System.currentTimeMillis() + SETTLE_MS
                while (heardFrom(MINI) == before && System.currentTimeMillis() < until) delay(POLL_MS)
                heardFrom(MINI) != before
            }
            assertTrue(heard, "torad took the collection in before its connection went")
        }
    }

    /** When torad last heard from [name], as its Sharing page reads it. */
    private fun heardFrom(name: String): String? =
        listener.clients.activity.now().firstOrNull { it.name == name }?.lastSeen

    /** torad's description, and mini's join there. */
    private fun joined(): Pair<ShareInfo, RemoteJoined.Joined> {
        val info = checkNotNull(RemoteHttp(RemoteTrust(null, "torad")).info("127.0.0.1", port).get())
        toradName = RemoteNames.of(info.server)
        return info to RemoteJoin(MINI).join(RemoteFound("127.0.0.1", port, info)) as RemoteJoined.Joined
    }

    /** mini offers torad [sessions] (id to name) itself, as any joined machine can, and torad's answer's status. */
    private fun offer(sessions: List<Pair<String, String>>): Int {
        val (info, joined) = joined()
        val http = HttpClient.newBuilder().sslContext(RemoteTrust(info.fingerprint, "torad").context).build()
        val body = buildJsonObject {
            putJsonArray("sessions") {
                sessions.forEach { (id, name) ->
                    addJsonObject {
                        put("id", id)
                        put("name", name)
                    }
                }
            }
        }
        val put = HttpRequest.newBuilder(URI.create("https://127.0.0.1:$port/share/v1/sessions"))
            .header("Authorization", "Bearer ${joined.token}")
            .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        return http.send(put, HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    /** A session name another machine might send: empty, long, or anything a person or a program could type. */
    private fun drawnName(random: Random): String {
        val alphabet = "abcXYZ019 ._-@<>&\"'/\\\n\t:;é漢🙂\u0000"
        return (0 until random.nextInt(0, 120)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    /** Waits, up to a deadline, until [wanted] holds of what [session] lists, and answers what it lists then. */
    private fun listed(session: ClaudeCodeSession, wanted: (Set<String>) -> Boolean): Set<String> = runBlocking {
        val until = System.currentTimeMillis() + SETTLE_MS
        var agents = session.agents()
        while (!wanted(agents) && System.currentTimeMillis() < until) {
            delay(POLL_MS)
            agents = session.agents()
        }
        agents
    }

    @Test
    fun `each machine lists the other's live sessions under that machine's name, and never its own back`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        join()
        val onMini = listed(splice) { "$toradName/rig-lead" in it }
        val onTorad = listed(rig) { "$MINI/splice" in it }
        assertEquals(setOf("$toradName/rig-lead"), onMini, "mini shows torad's session, and no mirror of its own")
        assertEquals(setOf("$MINI/splice"), onTorad, "torad shows mini's session, and no mirror of its own")
    }

    /** Waits, up to a deadline, until [machine] reads [name] at [now] as [wanted], and answers how it reads it then. */
    private fun reads(machine: Machine, name: String, now: Long, wanted: SessionAvailability) = runBlocking {
        val until = System.currentTimeMillis() + SETTLE_MS
        var read = machine.availability(name, now)
        while (read != wanted && System.currentTimeMillis() < until) {
            delay(POLL_MS)
            read = machine.availability(name, now)
        }
        read
    }

    @Test
    fun `a mirror reads stale or live as its session on the other machine does`() {
        val now = System.currentTimeMillis()
        val rig = torad.session("rig-lead", heardAt = now - HOUR_MS)
        mini.session("splice")
        join()
        val mirror = "$toradName/rig-lead"
        val idle = reads(mini, mirror, now, SessionAvailability.STALE)
        assertEquals(SessionAvailability.STALE, idle, "idle an hour there")

        rig.busyAt(now)
        val busy = reads(mini, mirror, now, SessionAvailability.LIVE)
        assertEquals(SessionAvailability.LIVE, busy, "busy there now")
    }

    @Test
    fun `a message crosses to the session named, and its reply comes back the same way`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        join()
        listed(splice) { "$toradName/rig-lead" in it }

        splice.send("$toradName/rig-lead", "hello from mini")
        val got = checkNotNull(rig.heard(SETTLE_MS / 1_000)) { "torad's session took the message" }
        assertEquals("hello from mini", got.text)
        assertEquals("$MINI/splice", got.fromName, "the sender is named as the other machine's session")

        rig.send(checkNotNull(got.from), "hello back")
        val back = checkNotNull(splice.heard(SETTLE_MS / 1_000)) { "the reply reached mini's session" }
        assertEquals("hello back", back.text)
        assertEquals("$toradName/rig-lead", back.fromName)
    }

    @Test
    fun `a note written while the joined machine's collection is cut reaches it at its next collection`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        val (info, joined) = joined()
        val links = links(joined)
        offered(links)
        listed(rig) { "$MINI/splice" in it }

        cutCollection(info, joined)
        rig.send("$MINI/splice", "while mini was away")
        link(links)
        val got = checkNotNull(splice.heard(SETTLE_MS / 1_000)) { "mini's session took the note" }
        assertEquals("while mini was away", got.text)
        assertEquals("$toradName/rig-lead", got.fromName)
    }

    @Test
    fun `a note waiting for the joined machine outlasts a change to what this machine shares`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        val links = links(joined().second)
        offered(links)
        listed(rig) { "$MINI/splice" in it }

        rig.send("$MINI/splice", "before the change")
        // A collection mini makes and never acknowledges, as one whose deliveries a restart cut short: it returns as
        // soon as the note waits, and leaves it there.
        val waiting = links.api(toradName).outbox(checkNotNull(links.url(toradName)), null)
        assertEquals(listOf("before the change"), waiting.notes.map { it.text }, "the note waits for mini")
        runBlocking { listener.follow(ShareConfig(enabled = true, port = port, hostnames = listOf("torad.lan"))) }
        link(links)
        val got = checkNotNull(splice.heard(SETTLE_MS / 1_000)) { "mini's session took the note" }
        assertEquals("before the change", got.text)
    }

    @Test
    fun `a session that ends leaves the other machine, and one that starts joins it`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        join()
        listed(splice) { "$toradName/rig-lead" in it }

        rig.close()
        val after = listed(splice) { "$toradName/rig-lead" !in it }
        assertTrue("$toradName/rig-lead" !in after, "an ended session is no longer listed on the other machine")

        torad.session("reviewer")
        assertTrue("$toradName/reviewer" in listed(splice) { "$toradName/reviewer" in it }, "a new session appears")
    }

    /** Waits, up to a deadline, until [machine]'s counts for [other] are [wanted], and answers them then. */
    private fun counted(machine: Machine, other: String, wanted: MirrorCounts) = runBlocking {
        val until = System.currentTimeMillis() + SETTLE_MS
        var counts = machine.mirrors.counts(other)
        while (counts != wanted && System.currentTimeMillis() < until) {
            delay(POLL_MS)
            counts = machine.mirrors.counts(other)
        }
        counts
    }

    @Test
    fun `each machine counts the sessions crossing with the other each way, until it stops looking`() {
        torad.session("rig-lead")
        mini.session("splice")
        mini.session("review")
        val link = join()

        assertEquals(MirrorCounts(here = 1, there = 2), counted(mini, toradName, MirrorCounts(1, 2)), "on mini")
        assertEquals(MirrorCounts(here = 2, there = 1), counted(torad, MINI, MirrorCounts(2, 1)), "on torad")

        runBlocking { link.cancelAndJoin() }
        val quiet = MirrorCounts(here = 0, there = null)
        assertEquals(quiet, counted(torad, MINI, quiet), "a machine that stopped looking is counted nothing either way")
    }

    @Test
    fun `a joined machine that stops looking leaves the sharing machine's listing`() {
        val rig = torad.session("rig-lead")
        mini.session("splice")
        val link = join()
        listed(rig) { "$MINI/splice" in it }

        runBlocking { link.cancelAndJoin() }
        val after = listed(rig) { "$MINI/splice" !in it }
        assertTrue("$MINI/splice" !in after, "a machine that stopped looking has no session listed on the other")
    }

    @Test
    fun `a machine that stops sharing and the machine that joined it each leave the other's listing`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        join()
        listed(splice) { "$toradName/rig-lead" in it }
        listed(rig) { "$MINI/splice" in it }

        runBlocking { listener.stop() }
        val onMini = listed(splice) { "$toradName/rig-lead" !in it }
        assertTrue("$toradName/rig-lead" !in onMini, "the joined machine no longer lists one that stopped sharing")
        val onTorad = listed(rig) { "$MINI/splice" !in it }
        assertTrue("$MINI/splice" !in onTorad, "the machine that stopped sharing no longer lists the joined one's")
    }

    @Test
    fun `whatever another machine names its sessions, each is listed here under a safe name of its own`() {
        val rig = torad.session("rig-lead")
        val random = Random(NAMES_SEED)
        val names = mutableListOf<String>()
        repeat(NAMES_DRAWN) { at ->
            // Every fifth session takes a name already drawn: two sessions there may share one.
            names += if (at % 5 == 4) names[random.nextInt(names.size)] else drawnName(random)
        }
        val sessions = names.map { UUID(random.nextLong(), random.nextLong()).toString() to it }
        assertEquals(200, offer(sessions))

        val listed = rig.agents().filter { it.startsWith("$MINI/") }
        assertEquals(NAMES_DRAWN, listed.size, "every session offered is listed")
        listed.forEach { assertTrue(SAFE_MIRROR.matches(it), "'$it' is a name ListAgents shows and a reply uses") }
        assertEquals(listed.size, listed.toSet().size, "no two are listed under one name")
    }

    @Test
    fun `a machine is mirrored no more sessions than the most one daemon hosts, however many it offers`() {
        val rig = torad.session("rig-lead")
        val sessions = (1..MIRRORS_AT_MOST + 44).map { UUID(0L, it.toLong()).toString() to "s$it" }
        assertEquals(200, offer(sessions))
        assertEquals(MIRRORS_AT_MOST, rig.agents().count { it.startsWith("$MINI/") })
    }

    @Test
    fun `a machine that has not joined is shown no sessions`() {
        torad.session("rig-lead")
        val info = checkNotNull(RemoteHttp(RemoteTrust(null, "torad")).info("127.0.0.1", port).get())
        val http = HttpClient.newBuilder().sslContext(RemoteTrust(info.fingerprint, "torad").context).build()
        val asked = HttpRequest.newBuilder(URI.create("https://127.0.0.1:$port/share/v1/sessions"))
            .header("Authorization", "Bearer not-a-joined-token").GET().build()
        val answer = http.send(asked, HttpResponse.BodyHandlers.ofString())
        assertEquals(401, answer.statusCode(), answer.body())
        assertTrue("rig-lead" !in answer.body())
    }
}
