// NEW: V4-391 holds the day-history hand-offs against legacy names and recipient references.
package splice.sessions.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.sessions.activity.MessageEdge
import splice.sessions.activity.NameHolders
import splice.sessions.activity.RecipientResolution
import splice.sessions.transcript.SentTexts
import java.nio.file.Files
import java.nio.file.Path

class TeamHistoryTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun `a day older than the configured edge window is not an empty conversation`() {
        val rig = TeamRig(tmp, retentionDays = 2)
        val team = rig.team()
        val routes = TeamsRoutes(
            teams = TeamSource { rig.store },
            heads = emptyMap(),
            registry = rig.registry,
            activity = ActivitySource { rig.stores },
            texts = SentTextSource { _, _, ids -> SentTexts(null, emptyMap(), ids) },
            clock = WallClock { AT },
        )
        val day = "2026-09-14"
        for (reply in listOf(routes.reads.chat(team.id, day), routes.reads.activity(team.id, day))) {
            val body = Json.parseToJsonElement(reply.body).jsonObject
            assertEquals("not_kept", body.getValue("state").jsonPrimitive.content)
            assertTrue(body.getValue("reason").jsonPrimitive.content.contains("kept"))
        }
    }

    @Test
    fun `a local day crossing the UTC cutoff keeps its later rows and names its missing beginning`() {
        val rig = TeamRig(tmp, retentionDays = 2)
        val team = rig.team()
        val hour = 60L * 60L * 1_000L
        val cutoff = rig.stores.oldestEdgeDay()
        rig.now = cutoff + hour
        rig.stores.edges.record(MessageEdge(LEAD, "uds:/run/2.sock", rig.now, "toolu_kept"))
        rig.stores.activity.label(LEAD, "claude", "Kept sample", rig.now)
        AsyncFileIo.drain()
        rig.now = AT
        val routes = TeamsRoutes(
            teams = TeamSource { rig.store },
            heads = emptyMap(),
            registry = rig.registry,
            activity = ActivitySource { rig.stores },
            texts = SentTextSource { _, _, ids -> SentTexts(null, emptyMap(), ids) },
            clock = WallClock { AT },
        )
        val from = (cutoff - 19 * hour).toString()
        val to = (cutoff + 5 * hour).toString()
        val chat = Json.parseToJsonElement(routes.reads.chat(team.id, null, from, to).body).jsonObject
        val activity = Json.parseToJsonElement(routes.reads.activity(team.id, null, from, to).body).jsonObject
        for (body in listOf(chat, activity)) {
            assertEquals("partially_kept", body.getValue("state").jsonPrimitive.content)
            assertTrue(body.getValue("reason").jsonPrimitive.content.contains("Earlier"))
            assertEquals(cutoff.toString(), body.getValue("oldest_kept_epoch_millis").jsonPrimitive.content)
        }
        assertEquals(1, chat.getValue("messages").jsonArray.size)
        assertEquals(1, activity.getValue("entries").jsonArray.size)
    }

    @Test
    fun `an explicit no-holder stays outside chat while a legacy name is recovered`() {
        val rig = TeamRig(tmp)
        val team = rig.team()
        rig.stores.edges.record(MessageEdge(BUILDER, "lead", AT, "toolu_legacy"))
        rig.stores.edges.record(
            MessageEdge(
                BUILDER,
                "lead",
                AT + 1,
                "toolu_no_holder",
                recipient = RecipientResolution.NoHolder,
            ),
        )
        AsyncFileIo.drain()
        val stored = Files.readAllLines(tmp.resolve("activity/edges-2026-09-18.jsonl"))
            .map { Json.parseToJsonElement(it).jsonObject }
        assertTrue("to_session" !in stored[0], "a legacy row has no resolution marker")
        assertEquals("null", stored[1].getValue("to_session").toString())
        val routes = TeamsRoutes(
            teams = TeamSource { rig.store },
            heads = emptyMap(),
            registry = rig.registry,
            activity = ActivitySource { rig.stores },
            texts = SentTextSource { _, _, ids -> SentTexts(null, emptyMap(), ids) },
            clock = WallClock { AT },
        )
        val messages = Json.parseToJsonElement(routes.reads.chat(team.id, null).body)
            .jsonObject.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(1, messages.size, "the explicit null cannot be reassigned to the current name holder")
        assertEquals("toolu_legacy", rig.stores.edges.edges().single { it.recipient == RecipientResolution.Legacy }.id)
    }

    @Test
    fun `a reference does not guess between two holders of the same name`() {
        val rig = TeamRig(tmp)
        for ((pid, id) in listOf(2 to BUILDER, 4 to "d4d4d4d4-0000-4000-8000-000000000004")) {
            Files.writeString(
                tmp.resolve("sessions/$pid.json"),
                """{"pid":$pid,"sessionId":"$id","cwd":"${rig.repo}","updatedAt":$AT,""" +
                    """"messagingSocketPath":"/run/$pid.sock","name":"builder"}""",
            )
        }
        val holders = NameHolders(rig.registry)
        assertEquals(null, holders.sessionOf("builder"))
        assertEquals(null, holders.sessionOf("builder [b2b2b2]"), "the ref is not a registry session prefix")
        assertEquals(null, holders.sessionOf("builder [aaaaaa]"))
    }

    @Test
    fun `an outsider with the same legacy name makes the recipient ambiguous`() {
        val rig = TeamRig(tmp)
        val team = rig.team()
        for ((pid, session) in listOf(2 to BUILDER, 3 to OUTSIDER)) {
            Files.writeString(
                tmp.resolve("sessions/$pid.json"),
                """{"pid":$pid,"sessionId":"$session","cwd":"${rig.repo}","updatedAt":$AT,""" +
                    """"messagingSocketPath":"/run/$pid.sock","name":"builder"}""",
            )
        }
        rig.stores.edges.record(MessageEdge(LEAD, "builder", AT, "toolu_ambiguous"))
        AsyncFileIo.drain()
        val routes = TeamsRoutes(
            teams = TeamSource { rig.store },
            heads = emptyMap(),
            registry = rig.registry,
            activity = ActivitySource { rig.stores },
            texts = SentTextSource { _, _, ids -> SentTexts(null, emptyMap(), ids) },
            clock = WallClock { AT },
        )
        val messages = Json.parseToJsonElement(routes.reads.chat(team.id, null).body)
            .jsonObject.getValue("messages").jsonArray
        assertEquals(0, messages.size, "a unique team holder is not enough when the registry has two holders")
    }

    @Test
    fun `a legacy subagent name never becomes a team hand-off when a member shares it`() {
        val rig = TeamRig(tmp)
        val team = rig.team()
        Files.writeString(
            tmp.resolve("sessions/2.json"),
            """{"pid":2,"sessionId":"$BUILDER","cwd":"${rig.repo}","updatedAt":$AT,""" +
                """"messagingSocketPath":"/run/2.sock","name":"main"}""",
        )
        rig.stores.edges.record(MessageEdge(LEAD, "main", AT, "toolu_subagent"))
        AsyncFileIo.drain()
        val routes = TeamsRoutes(
            teams = TeamSource { rig.store },
            heads = emptyMap(),
            registry = rig.registry,
            activity = ActivitySource { rig.stores },
            texts = SentTextSource { _, _, ids -> SentTexts(null, emptyMap(), ids) },
            clock = WallClock { AT },
        )
        val messages = Json.parseToJsonElement(routes.reads.chat(team.id, null).body)
            .jsonObject.getValue("messages").jsonArray
        assertEquals(0, messages.size)
    }

    @Test
    fun `legacy name edges reach a member while subagent names stay outside chat`() {
        val rig = TeamRig(tmp)
        val team = rig.team()
        Files.writeString(
            tmp.resolve("sessions/2.json"),
            """{"pid":2,"sessionId":"$BUILDER","cwd":"${rig.repo}","updatedAt":$AT,""" +
                """"messagingSocketPath":"/run/2.sock","name":"builder"}""",
        )
        for ((index, recipient) in listOf("builder", "builder [9c0d12]", "main", "code-review").withIndex()) {
            rig.stores.edges.record(MessageEdge(LEAD, recipient, AT + index, "toolu_history_$index"))
        }
        AsyncFileIo.drain()
        val routes = TeamsRoutes(
            teams = TeamSource { rig.store },
            heads = emptyMap(),
            registry = rig.registry,
            activity = ActivitySource { rig.stores },
            texts = SentTextSource { _, _, ids -> SentTexts(null, emptyMap(), ids, listOf("/old/one", "/old/two")) },
            clock = WallClock { AT },
        )
        val messages = Json.parseToJsonElement(routes.reads.chat(team.id, null).body)
            .jsonObject.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(2, messages.size, "old names and ref names are hand-offs, subagents are not")
        assertTrue(messages.all { it.getValue("to_slot").jsonPrimitive.content == "b1" })
        assertTrue(messages.all { !it.getValue("missing_reason").jsonPrimitive.content.contains("/old/") })
        assertEquals(
            BUILDER,
            NameHolders(rig.registry).sessionOf("builder [9c0d12]"),
            "a referenced recipient is recorded under the same live member",
        )
    }
}
