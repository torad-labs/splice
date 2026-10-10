package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.sessions.activity.ActivityStores
import splice.sessions.activity.MessageEdge
import splice.sessions.transcript.SentTexts
import java.nio.file.Files
import java.nio.file.Path

class MessageEdgesSwitchRoutesTest {
    private fun stores(root: Path, messageEdges: Boolean = true): ActivityStores = ActivityStores(
        root.resolve("activity"),
        7,
        "*",
        WallClock { AT },
        messageEdges = messageEdges,
    )

    private fun field(body: String, name: String): String =
        Json.parseToJsonElement(body).jsonObject.getValue(name).jsonPrimitive.content

    @Test
    fun `off writes no edges but reports off on every session and team reader`(@TempDir root: Path) {
        val rig = TeamRig(root)
        val team = rig.team()
        val stores = stores(root, messageEdges = false)
        stores.edges.record(MessageEdge(LEAD, BUILDER, AT, "toolu_off"))
        assertTrue(AsyncFileIo.drain())
        assertFalse(Files.exists(root.resolve("activity/edges-2026-09-18.jsonl")))

        val source = ActivitySource { stores }
        val text = SentTextSource { _, _, ids -> SentTexts(null, emptyMap(), ids) }
        val edges = ActivityRoutes(rig.registry, source, text)
        val sessions = SessionsRoutes(rig.registry, TestTranscripts(), activity = source)
        val teams = TeamReads(TeamSource { rig.store }, rig.registry, source, text, WallClock { AT })
        val json = Json
        fun state(body: String) = json.parseToJsonElement(body).jsonObject.getValue("state").jsonPrimitive.content
        assertEquals("off", state(edges.edges(LEAD).body))
        assertEquals("off", state(edges.boardEdges().body))
        assertEquals("edges off", field(edges.boardEdges().body, "reason"))
        val listing = json.parseToJsonElement(sessions.sessionsJson()).jsonObject
        assertEquals("off", listing.getValue("edges_state").jsonPrimitive.content)
        assertTrue(
            listing.getValue("sessions").jsonArray.all { row ->
                row.jsonObject.getValue("edges_state").jsonPrimitive.content == "off"
            },
        )
        assertEquals("off", state(teams.edges(team.id).body))
        assertEquals("off", state(teams.chat(team.id, null).body))
    }

    @Test
    fun `delete reports physical counts and both readers say deleted until another row lands`(@TempDir root: Path) {
        val rig = TeamRig(root)
        val team = rig.team()
        val stores = stores(root)
        stores.edges.record(MessageEdge(LEAD, BUILDER, AT, "toolu_1"))
        stores.activity.label(LEAD, "claude", "Reading README.md", AT)
        assertTrue(AsyncFileIo.drain())
        val planted = root.resolve("activity/leave-me.txt")
        Files.writeString(planted, "keep")
        val outside = root.resolve("outside.jsonl")
        Files.writeString(outside, "private target")
        Files.createSymbolicLink(root.resolve("activity/edges-2026-09-19.jsonl"), outside)

        val source = ActivitySource { stores }
        val text = SentTextSource { _, _, ids -> SentTexts(null, emptyMap(), ids) }
        val edges = ActivityRoutes(rig.registry, source, text)
        val teams = TeamReads(TeamSource { rig.store }, rig.registry, source, text, WallClock { AT })
        assertEquals("1", field(edges.kept(KeptActivity.EDGES).body, "rows"))
        // the size the store costs on disk, beside what it holds: Settings > Your data reports both, and a row count
        // cannot say what keeping it costs
        val edgeBytes = field(edges.kept(KeptActivity.EDGES).body, "bytes").toLong()
        assertTrue(edgeBytes > 0, "a store holding a row holds bytes")
        assertEquals("2026-09-18", field(edges.kept(KeptActivity.EDGES).body, "oldest"))
        assertEquals("2026-09-25", field(edges.kept(KeptActivity.EDGES).body, "ages_out"))
        assertEquals("1", field(edges.kept(KeptActivity.LABELS).body, "rows"))
        assertEquals("2026-09-20", field(edges.kept(KeptActivity.LABELS).body, "ages_out"))
        val deletedEdges = edges.deleteKept(KeptActivity.EDGES)
        assertEquals(HttpStatusCode.OK, deletedEdges.status, deletedEdges.body)
        assertEquals("1", field(deletedEdges.body, "rows"))
        assertEquals("deleted", field(edges.edges(LEAD).body, "state"))
        assertEquals("edges deleted", field(edges.edges(LEAD).body, "reason"))
        assertEquals("deleted", field(teams.chat(team.id, null).body, "state"))
        assertEquals("0", field(edges.kept(KeptActivity.EDGES).body, "rows"))
        assertEquals("0", field(edges.kept(KeptActivity.EDGES).body, "bytes"), "a deleted store costs nothing")
        val deletedLabels = edges.deleteKept(KeptActivity.LABELS)
        assertEquals("1", field(deletedLabels.body, "rows"))
        assertEquals("deleted", field(teams.activity(team.id, null).body, "state"))
        assertEquals("labels deleted", field(teams.activity(team.id, null).body, "reason"))
        assertEquals("keep", Files.readString(planted))
        assertEquals("private target", Files.readString(outside))

        stores.edges.record(MessageEdge(LEAD, BUILDER, AT, "toolu_after"))
        assertTrue(AsyncFileIo.drain())
        assertEquals("on", field(edges.edges(LEAD).body, "state"))
        assertEquals("1", field(edges.kept(KeptActivity.EDGES).body, "rows"))
    }

    @Test
    fun `delete clears retained edges with the switch off without turning it on`(@TempDir root: Path) {
        val rig = TeamRig(root)
        val before = stores(root)
        before.edges.record(MessageEdge(LEAD, BUILDER, AT, "toolu_before"))
        assertTrue(AsyncFileIo.drain())
        val off = stores(root, messageEdges = false)
        val text = SentTextSource { _, _, ids -> SentTexts(null, emptyMap(), ids) }
        val routes = ActivityRoutes(rig.registry, ActivitySource { off }, text)
        assertEquals("off", field(routes.kept(KeptActivity.EDGES).body, "state"))
        assertEquals("1", field(routes.kept(KeptActivity.EDGES).body, "rows"))
        assertEquals("1", field(routes.deleteKept(KeptActivity.EDGES).body, "rows"))
        off.edges.record(MessageEdge(LEAD, BUILDER, AT, "toolu_ignored"))
        assertTrue(AsyncFileIo.drain())
        assertEquals("deleted", field(routes.edges(LEAD).body, "state"))
        assertEquals("0", field(routes.kept(KeptActivity.EDGES).body, "rows"))
    }

    @Test
    fun `label delete works after all heads stop storing labels`(@TempDir root: Path) {
        val rig = TeamRig(root)
        val active = stores(root)
        active.activity.label(LEAD, "claude", "Reading README.md", AT)
        assertTrue(AsyncFileIo.drain())
        val off = ActivityStores(root.resolve("activity"), 7, "", WallClock { AT })
        val text = SentTextSource { _, _, ids -> SentTexts(null, emptyMap(), ids) }
        val routes = ActivityRoutes(rig.registry, ActivitySource { off }, text)
        assertEquals("off", field(routes.kept(KeptActivity.LABELS).body, "state"))
        assertEquals("1", field(routes.deleteKept(KeptActivity.LABELS).body, "rows"))
        assertEquals("deleted", field(routes.kept(KeptActivity.LABELS).body, "state"))
        assertEquals("0", field(routes.kept(KeptActivity.LABELS).body, "rows"))
    }
}
