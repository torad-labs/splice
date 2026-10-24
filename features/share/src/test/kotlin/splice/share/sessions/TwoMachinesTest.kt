// NEW: LAN share, phase 2, milestone 6 (docs/specs/lan-share.md). Two machines in one JVM, each with its own Claude Code
// registry, sockets folder and state: "torad" shares over its real TLS listener, and "mini" joined it and runs the
// daemon's session link, as the daemon runs both. The sessions are Claude Code's own shapes (ClaudeCodeSession);
// everything between them is splice's real code. The cases are in SessionsAcrossMachinesTest and, for the Sessions
// switch, SessionsSwitchAcrossMachinesTest; this is the rig they share. Since round 14 of PR #419's review a test can
// move both machines' clocks on ([skew]): a lease of tens of seconds runs out without the test waiting for it.
package splice.share.sessions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.core.topology.RemoteConfig
import splice.core.topology.ShareConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.core.util.WallClock
import splice.sessions.mirror.MirrorAnchor
import splice.sessions.mirror.MirrorPlace
import splice.sessions.mirror.MirrorSweep
import splice.sessions.mirror.SessionMirrors
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.share.MachinesJoined
import splice.share.SessionsSwitch
import splice.share.ShareInfo
import splice.share.ShareListener
import splice.share.ShareSession
import splice.share.ShareStart
import splice.share.ShareState
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
import splice.upstream.codemode.ProcessTicker
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import kotlin.random.Random

// why: how often the link looks in this test; the daemon's own cadence is what the goal's ten seconds rest on.
internal const val LOOK_MS = 200L

// why: a look slow enough that the mirrors one offer makes last the whole test: three looks, capped at a minute.
internal const val SLOW_LOOK_MS = 20_000L

// why: how often a test reads the registry while it waits for the next look.
internal const val POLL_MS = 50L

// why: enough of the link's looks with a switch off that a request it made would have been heard.
internal const val LOOKS_OFF = 3

// why: how long a test waits for what the next few looks bring before it fails.
internal const val SETTLE_MS = 15_000L

// why: well past the half hour after which splice reads a session it has not heard from as stale.
internal const val HOUR_MS = 3_600_000L

// why: the client name the joined machine gives, as `splice remote` gives its host name.
internal const val MINI = "mini"

// why: the host name of a machine that goes by capitals, as a Mac does ("Marcoss-Mini"): it joins under it, and it is
// the name its listener gives, which the machine that joins it back keys it by.
internal const val MIXED = "Mini-Mac"

// why: the most mirrors one daemon hosts: every machine on the network may join, and each mirror is a process.
internal const val MIRRORS_AT_MOST = 256

// why: a fixed seed, so a failing draw of session names is the same draw on the next run.
internal const val NAMES_SEED = 23

// why: enough sessions that equal names, empty ones and long ones all come up in one draw.
internal const val NAMES_DRAWN = 60

internal abstract class TwoMachinesTest {

    @TempDir
    lateinit var tmp: Path

    /** One machine: its Claude Code registry, its sockets folder (owner-only, with short names, so a socket path fits
     *  the 104 bytes a Mac allows), its state, and the mirrors its daemon hosts there, each holding a pid by
     *  [anchor], or by the daemon's own when none is given, and timed by the test's clock, which [skew] moves on. */
    protected inner class Machine(letter: String, anchor: MirrorAnchor? = null) {
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

        /** Where its daemon keeps what it acknowledged of each machine's outbox. */
        val acks: Path = ShareState(paths).acksFile
        private val place = MirrorPlace(registry, sockets, home.resolve("m.log"))
        private val sweep = MirrorSweep { ProcessTicker().awaitTick(it) }
        private val clock = WallClock { System.currentTimeMillis() + skew.get() }
        val mirrors = if (anchor == null) {
            SessionMirrors(place, scope, Dispatchers.IO, { }, sweep, clock)
        } else {
            SessionMirrors(place, scope, Dispatchers.IO, { }, sweep, clock, anchor)
        }

        fun session(name: String, heardAt: Long? = null, id: String = UUID.randomUUID().toString()) =
            ClaudeCodeSession(registry, sockets, name, heardAt, id).also(opened::add)

        /** How splice's Sessions page here reads the session named [name] at [now]: null when it is not listed. */
        fun availability(name: String, now: Long): SessionAvailability? =
            SessionRegistry(registry, { SessionRoute.Unknown }, clock = { now }).read()
                .firstOrNull { it.name == name }?.availability
    }

    protected val opened = mutableListOf<AutoCloseable>()

    /** How far the test moved both machines' clocks past the wall's: the time a lease runs by, without waiting it. */
    protected val skew = AtomicLong()
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    protected lateinit var torad: Machine
    protected lateinit var mini: Machine
    protected lateinit var listener: ShareListener
    protected var port = 0

    /** What mini calls torad: its `[remotes]` key, from the name torad gives. */
    protected lateinit var toradName: String

    @BeforeEach
    fun machines() {
        torad = Machine("t")
        mini = Machine("m")
        port = TestPorts.reserve()
        listener = sharing(torad, port)
    }

    /** [machine] sharing on [at], as its daemon opens the listener over the splice.toml it booted, [topology]. */
    protected fun sharing(machine: Machine, at: Int, topology: Topology = Topology()): ShareListener {
        val paths = machine.paths
        val sharing = ShareListener(
            ShareStart(topology, paths, MgmtKey(paths), ConfigService(paths)) { },
            SharedCatalogs { null },
            SharedQuotas { null },
            paths,
            MachinesJoined { topology.remotes.keys },
            { },
            machine.mirrors,
        )
        runBlocking { sharing.follow(ShareConfig(enabled = true, port = at)) }
        return sharing
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
    protected fun join(): Job = link(links(joined().second))

    /** mini's links once it kept [joined]'s token and pin, as `splice remote` keeps them, with torad's Sessions switch
     *  on there unless [sessions] says off. */
    protected fun links(joined: RemoteJoined.Joined, sessions: Boolean = true): RemoteLinks =
        links(mini, toradName, joined, port, sessions)

    /** [machine]'s links once it kept the token and pin of its join to [name], which shares on [at], with [name]'s
     *  Sessions switch on there as [sessions] says. */
    protected fun links(
        machine: Machine,
        name: String,
        joined: RemoteJoined.Joined,
        at: Int,
        sessions: Boolean = true,
    ): RemoteLinks {
        val store = store(machine)
        store.save(name, joined.token)
        if (sessions) store.allowSessions(name, true)
        val booted = joinedTo(name, joined, at)
        val switch = SessionsSwitch(ShareState(machine.paths), MachinesJoined { booted.remotes.keys })
        return RemoteLinks(booted, store, switch)
    }

    /** The splice.toml of a machine that joined [name], which shares on [at], as `splice remote` writes it: its
     *  `[remotes]` entry, with [joined]'s address and pin. */
    protected fun joinedTo(name: String, joined: RemoteJoined.Joined, at: Int): Topology =
        Topology(remotes = mapOf(name to RemoteConfig(joined.http.base("127.0.0.1", at), joined.pin)))

    /** [machine]'s own tokens on the machines it joined, and their Sessions switches, as its Sharing page turns them. */
    protected fun store(machine: Machine) =
        RemotesStore(machine.paths.stateDir.resolve("share").resolve("remotes.json"))

    /** [machine]'s daemon starting its session link over [links]. */
    protected fun link(links: RemoteLinks, machine: Machine = mini): Job =
        checkNotNull(RemoteSessions(links, machine.mirrors, machine.acks, { }, LOOK_MS).start(scope))

    /** mini offers torad its running sessions once, as a look does, with a look slow enough that torad keeps their
     *  mirrors through the test without another. */
    protected fun offered(links: RemoteLinks) {
        val ours = mini.mirrors.local().map { ShareSession(it.id, it.name, it.heardAt) }
        links.api(toradName).offer(checkNotNull(links.url(toradName)), ours, SLOW_LOOK_MS)
    }

    /** A collection of mini's outbox that torad takes in and starts to hold, and whose connection then goes, as when
     *  mini sleeps, its daemon restarts or its Wi-Fi drops. */
    protected fun cutCollection(info: ShareInfo, joined: RemoteJoined.Joined) {
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
    protected fun heardFrom(name: String): String? =
        listener.clients.activity.now().firstOrNull { it.name == name }?.lastSeen

    /** torad's description, and mini's join there under [client], with mini's Sessions switch on at torad unless
     *  [sessions] says off. */
    protected fun joined(client: String = MINI, sessions: Boolean = true): Pair<ShareInfo, RemoteJoined.Joined> {
        val info = described(port)
        toradName = RemoteNames.of(info.server)
        val joined = RemoteJoin(client).join(RemoteFound("127.0.0.1", port, info)) as RemoteJoined.Joined
        if (sessions) assertTrue(listener.clients.allowSessions(RemoteNames.of(client), true), "$client joined")
        return info to joined
    }

    /** The description of the machine sharing on [at], as a scan reads it before it trusts any certificate. */
    protected fun described(at: Int): ShareInfo =
        checkNotNull(RemoteHttp(RemoteTrust(null, "torad")).info("127.0.0.1", at).get())

    /** mini offers torad [sessions] (id to name) itself, as any joined machine can, over [joining], and torad's answer's
     *  status. */
    protected fun offer(
        sessions: List<Pair<String, String>>,
        joining: Pair<ShareInfo, RemoteJoined.Joined> = joined(),
    ): Int {
        val (info, joined) = joining
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
    protected fun drawnName(random: Random): String {
        val alphabet = "abcXYZ019 ._-@<>&\"'/\\\n\t:;é漢🙂\u0000"
        return (0 until random.nextInt(0, 120)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    /** Waits, up to a deadline, until [wanted] holds of what [session] lists, and answers what it lists then. */
    protected fun listed(session: ClaudeCodeSession, wanted: (Set<String>) -> Boolean): Set<String> = runBlocking {
        val until = System.currentTimeMillis() + SETTLE_MS
        var agents = session.agents()
        while (!wanted(agents) && System.currentTimeMillis() < until) {
            delay(POLL_MS)
            agents = session.agents()
        }
        agents
    }
}
