// NEW: LAN share, phase 2. The Sharing page's machine cards THROUGH A REAL ControlServer over HTTP (SharingRoutesFixture):
// how many sessions cross with each machine, and each card's Sessions switch, off until Marcos turns it on and turning
// only its own book (the round-1, round-3 and round-5 reviews). Round 14 of PR #419's review: the page acts on the
// splice.toml the daemon booted, whatever file its environment names, and a Sessions switch turned from a card names
// the join the card showed, so a machine that joined since under that name is not let in by it.
package splice.app.control

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.sessions.mirror.MirrorCarried
import splice.sessions.mirror.MirrorCarrier
import splice.sessions.mirror.MirrorLink
import splice.sessions.mirror.MirrorPlace
import splice.sessions.mirror.MirrorSweep
import splice.sessions.mirror.MirrorWay
import splice.sessions.mirror.MirroredSession
import splice.sessions.mirror.SessionMirrors
import splice.share.ShareClients
import splice.share.ShareState
import splice.share.remote.RemotesStore
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

internal class SharingMachinesTest : SharingRoutesFixture() {

    // LAN share, milestone 6: each machine sessions cross with says how many of its sessions are listed here and how many
    // of this machine's it was last shown; one nothing crossed with yet says so by leaving the second out.
    @Test
    fun `each machine says how many of its sessions are listed here and how many of ours it was shown`() {
        Files.writeString(file, Files.readString(file) + "\n[remotes.far]\nurl = \"https://far.lan:3097\"\n")
        val paths = StatePaths(envReader = env)
        val clients = ShareState(paths).clientsFile
        Files.createDirectories(clients.parent)
        Files.writeString(
            clients,
            """{"clients":[{"name":"mini","token_sha256":"00","address":"10.0.0.2",""" +
                """"joined_at":"2026-10-23T12:00:00Z"}]}""",
        )
        val sockets = Files.createDirectory(
            tmp.resolve("s"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val place = MirrorPlace(tmp.resolve("r"), sockets, tmp.resolve("m.log"))
        // Its mirrors last longer than the test, so they never look for ended ones.
        val mirrors = SessionMirrors(place, scope, Dispatchers.IO, { }, MirrorSweep { false })
        Files.createDirectories(tmp.resolve("r"))
        val carrier = MirrorCarrier { _, _, _ -> MirrorCarried(null) }
        try {
            val offered = listOf(MirroredSession("a", "splice"), MirroredSession("b", "review"))
            mirrors.mirror("mini", offered, MirrorLink(carrier, MIRRORS_LAST_MS, MirrorWay.OFFER))
            mirrors.shown("mini", 1)
            val far = MirrorLink(carrier, MIRRORS_LAST_MS, MirrorWay.LOOK)
            mirrors.mirror("far", listOf(MirroredSession("c", "rig-lead")), far)
            serve(mirrors = mirrors) { call ->
                val view = read(call)
                val mini = view.getValue("joined").jsonArray.single().jsonObject
                assertEquals(2, mini.getValue("sessions_here").jsonPrimitive.int, "mini's two sessions are listed here")
                assertEquals(1, mini.getValue("sessions_there").jsonPrimitive.int, "mini was shown this machine's one")
                val far = view.getValue("remotes").jsonArray.single().jsonObject
                assertEquals(1, far.getValue("sessions_here").jsonPrimitive.int)
                assertFalse("sessions_there" in far, "far has not been shown this machine's sessions yet")
            }
        } finally {
            mirrors.close()
            scope.cancel()
        }
    }

    // The round-1 review's second finding, as the President decided it (Oct 23): no joined machine can list, write into
    // or read from this machine's sessions until Marcos turns that machine's switch on, in either direction of the pair.
    @Test
    fun `each machine's Sessions switch reads off until the page turns it on, on either side, and off again`() {
        Files.writeString(file, Files.readString(file) + "\n[remotes.far]\nurl = \"https://far.lan:3097\"\n")
        val state = ShareState(StatePaths(envReader = env))
        Files.createDirectories(state.clientsFile.parent)
        Files.writeString(
            state.clientsFile,
            """{"clients":[{"name":"mini","token_sha256":"00","address":"10.0.0.2",""" +
                """"joined_at":"2026-10-23T12:00:00Z"}]}""",
        )
        RemotesStore(state.remotesFile).save("far", "far-token")
        RemotesStore(state.remotesFile).save("ghost", "a token kept from before")
        serve { call ->
            assertEquals(listOf(false, false), switches(read(call)), "off on both sides until the page turns it on")
            val unkeyed = call(HttpMethod.Put, "/api/share/machines/mini/sessions", "{\"on\":true}", false)
            assertEquals(401, unkeyed.status.value, "the switch is guarded")

            assertTrue(switched(call, "/api/share/machines/mini/sessions", true).getValue("ok").jsonPrimitive.boolean)
            assertEquals(listOf(true, false), switches(read(call)), "mini may cross sessions with this machine")
            assertTrue(switched(call, "/api/remotes/far/sessions", true).getValue("ok").jsonPrimitive.boolean)
            assertEquals(listOf(true, true), switches(read(call)), "and this machine with far")
            assertTrue(RemotesStore(state.remotesFile).sessions("far"), "where the daemon's link reads it")

            switched(call, "/api/share/machines/mini/sessions", false)
            switched(call, "/api/remotes/far/sessions", false)
            assertEquals(listOf(false, false), switches(read(call)), "and both off again")

            // Each card turns its own book: a name only the other book holds is a machine this card does not know.
            // Nor is a name whose token outlived its `[remotes]` entry a machine this one joined (the round-4 review).
            val elsewhere = listOf(
                "share/machines/nobody",
                "share/machines/far",
                "remotes/nobody",
                "remotes/mini",
                "remotes/ghost",
            )
            for (path in elsewhere.map { "/api/$it/sessions" }) {
                val refused = switched(call, path, true)
                assertFalse(refused.getValue("ok").jsonPrimitive.boolean, path)
                val said = refused.getValue("said").jsonArray.single().jsonObject
                val named = path.split("/").dropLast(1).last()
                assertTrue(named in said.getValue("text").jsonPrimitive.content, said.toString())
            }
            assertEquals(listOf(false, false), switches(read(call)), "and turned nothing")
            val unread = call(HttpMethod.Put, "/api/share/machines/mini/sessions", "on", true)
            assertEquals(400, unread.status.value, "a body the route cannot read is told what to send")
        }
    }

    // The round-3 review: anyone on the network may join under any name, so the machine on one card may not be the one
    // on the other. Each card turns only its own book, as it shows it, and sessions cross while both are on. This
    // replaces round 2's one switch turned from either card, which let a device that joined under a used machine's name
    // in through the card of the machine it named.
    @Test
    fun `a machine that joined this one and that this one joined crosses sessions only while both its cards are on`() {
        Files.writeString(file, Files.readString(file) + "\n[remotes.both]\nurl = \"https://both.lan:3097\"\n")
        val state = ShareState(StatePaths(envReader = env))
        Files.createDirectories(state.clientsFile.parent)
        Files.writeString(
            state.clientsFile,
            """{"clients":[{"name":"both","token_sha256":"00","address":"10.0.0.3",""" +
                """"joined_at":"2026-10-23T12:00:00Z"}]}""",
        )
        RemotesStore(state.remotesFile).save("both", "both-token")
        val clients = ShareClients(state.clientsFile)
        serve { call ->
            assertTrue(switched(call, "/api/share/machines/both/sessions", true).getValue("ok").jsonPrimitive.boolean)
            assertEquals(listOf(true, false), switches(read(call)), "the card turned shows On, the other still Off")
            assertEquals(listOf(false, false), switches(read(call), "crossing"), "and nothing crosses yet")
            assertFalse(RemotesStore(state.remotesFile).sessions("both"), "an On on one card turns no other")

            assertTrue(switched(call, "/api/remotes/both/sessions", true).getValue("ok").jsonPrimitive.boolean)
            assertEquals(listOf(true, true), switches(read(call)))
            assertEquals(listOf(true, true), switches(read(call), "crossing"), "both on, sessions cross")

            // A re-join turns its own card off, and nothing crosses, whichever card is read.
            assertTrue(clients.allowSessions("both", false))
            assertEquals(listOf(false, true), switches(read(call)))
            assertEquals(listOf(false, false), switches(read(call), "crossing"), "off on one card stops both")
            switched(call, "/api/remotes/both/sessions", false)
            assertEquals(listOf(false, false), switches(read(call)))
            assertFalse(clients.all().single().sessions, "and the joined card stayed as it was")
        }
    }

    // Round 14: a page drawn before a machine was revoked and something joined under its name turns no switch of the
    // machine that joined since, which starts off: the card's switch names the join it showed.
    @Test
    fun `a switch turned from a card drawn before the machine joined again lets in nothing that joined since`() {
        val state = ShareState(StatePaths(envReader = env))
        Files.createDirectories(state.clientsFile.parent)
        val booked = { at: String ->
            """{"clients":[{"name":"mini","token_sha256":"00","address":"10.0.0.2","joined_at":"$at"}]}"""
        }
        Files.writeString(state.clientsFile, booked(JOINED_AT))
        serve { call ->
            val shown = read(call).getValue("joined").jsonArray.single().jsonObject
            val card = shown.getValue("joined_at").jsonPrimitive.content
            val since = "2026-10-24T08:00:00Z"
            Files.writeString(state.clientsFile, booked(since))

            val stale = switched(call, "/api/share/machines/mini/sessions", true, joined = card)
            assertFalse(stale.getValue("ok").jsonPrimitive.boolean, "the card's machine is not the one booked now")
            val said = stale.getValue("said").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            assertTrue("joined again" in said, said)
            assertFalse(ShareClients(state.clientsFile).all().single().sessions, "the machine that joined since is off")

            val current = switched(call, "/api/share/machines/mini/sessions", true, joined = since)
            assertTrue(current.getValue("ok").jsonPrimitive.boolean, current.toString())
            assertTrue(ShareClients(state.clientsFile).all().single().sessions, "a card that shows it turns it on")
        }
    }

    // Round 14: the daemon booted one splice.toml, and its own environment may name another (or none): the page reads
    // and changes the one the daemon booted, so an import, a sync or a remove reaches the daemon's links and switches.
    @Test
    fun `the page reads and changes the splice toml the daemon booted, not one its environment names`() {
        val far = "\n[remotes.booted-far]\nurl = \"https://booted-far.lan:3097\"\n"
        Files.writeString(file, Files.readString(file) + far)
        val elsewhere = tmp.resolve("elsewhere.toml").also { Files.writeString(it, shareToml) }
        val vars = mapOf(
            "SPLICE_CONFIG" to elsewhere.toString(),
            "SPLICE_STATE_DIR" to tmp.resolve("state").toString(),
            "HOME" to tmp.toString(),
        )
        serve(environment = EnvReader { vars[it] }) { call ->
            val remotes = read(call).getValue("remotes").jsonArray
                .map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertEquals(listOf("booted-far"), remotes, "the page lists the file the daemon booted")

            val removed = call(HttpMethod.Delete, "/api/remotes/booted-far", null, true)
            assertEquals(200, removed.status.value, removed.bodyAsText())
            assertFalse("booted-far" in Files.readString(file), "removed from the file the daemon booted")
            assertEquals(shareToml, Files.readString(elsewhere), "and the other file is as it was")
        }
    }

    // The round-5 review: a machine imported from the Sharing page has its card at once, before splice restarts and
    // starts its link, so its Sessions switch is one at once too. Its On is kept, and while it is off nothing crosses
    // with that machine, whichever of its cards is read.
    @Test
    fun `a machine imported with no restart has a Sessions switch at once, and nothing crosses while it is off`() {
        val state = ShareState(StatePaths(envReader = env))
        Files.createDirectories(state.clientsFile.parent)
        Files.writeString(
            state.clientsFile,
            """{"clients":[{"name":"both","token_sha256":"00","address":"10.0.0.3",""" +
                """"joined_at":"2026-10-23T12:00:00Z"}]}""",
        )
        serve { call ->
            assertTrue(switched(call, "/api/share/machines/both/sessions", true).getValue("ok").jsonPrimitive.boolean)
            val joined = read(call).getValue("joined").jsonArray.single().jsonObject
            assertTrue(joined.getValue("crossing").jsonPrimitive.boolean, "it joined this machine, and its card is on")

            // Marcos uses its commands: its `[remotes]` entry is written and its token kept, and splice runs on.
            Files.writeString(file, Files.readString(file) + "\n[remotes.both]\nurl = \"https://both.lan:3097\"\n")
            RemotesStore(state.remotesFile).save("both", "both-token")
            assertEquals(listOf(true, false), switches(read(call)), "its new card reads Off")
            assertEquals(listOf(false, false), switches(read(call), "crossing"), "and nothing crosses, as it says")

            assertTrue(switched(call, "/api/remotes/both/sessions", true).getValue("ok").jsonPrimitive.boolean, "kept")
            assertTrue(RemotesStore(state.remotesFile).sessions("both"), "where its link reads it once it starts")
            assertEquals(listOf(true, true), switches(read(call), "crossing"), "both on, sessions cross")
        }
    }
}
