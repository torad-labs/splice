// NEW: V4-131 — TeamStore keeps the operator's teams in teams.json: minted ids, compositions whose
// bindings survive a re-save, a binding history that a rebind only ever grows, refusals that name why,
// and a file that is never replaced by an empty list when it does not parse.
package splice.core.teams

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions

private const val T0 = 1_789_725_600_000L
private const val S1 = "a1a1a1a1-0000-4000-8000-000000000001"
private const val S2 = "b2b2b2b2-0000-4000-8000-000000000002"

class TeamStoreTest {

    @TempDir
    lateinit var dir: Path

    private var now = T0
    private val file get() = dir.resolve("teams.json")
    private fun store() = TeamStore(file, WallClock { now })

    private fun team(vararg slots: TeamSlot, id: String = "") =
        Team(id = id, name = "atlas", goal = "ship", repo = "/w/repo", slots = slots.toList())

    private val lead = TeamSlot(id = "lead", role = "orchestrator", head = "claude", lead = true)
    private val builder = TeamSlot(id = "b1", role = "builder", head = "codex")

    @Test
    fun `an upsert mints the id, stamps the clock, and writes a 0600 file a fresh store reads back`() {
        val saved = store().upsert(team(lead, builder))
        assertTrue(Regex("team-[0-9a-f]{12}").matches(saved.id), saved.id)
        assertEquals(T0, saved.createdAt)
        assertEquals(T0, saved.updatedAt)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals(listOf(saved), store().teams())
        assertTrue(Files.readString(file).contains("\"created_epoch_millis\""), "snake_case with the unit in the name")
    }

    @Test
    fun `a re-save keeps bindings, created time and history, and stamps only an instructions change`() {
        val s = store()
        val id = s.upsert(team(lead, builder)).id
        s.bind(id, mapOf("b1" to S1))
        now = T0 + 1000
        val resaved = s.upsert(team(lead, builder.copy(instructions = "run the tests"), id = id))
        val b1 = resaved.slots.single { it.id == "b1" }
        assertEquals(S1, b1.session, "the composer's re-save never unbinds a live session")
        assertEquals(listOf(S1), b1.sessionsHistory)
        assertEquals(T0 + 1000, b1.instructionsUpdatedAt)
        assertNull(resaved.slots.single { it.id == "lead" }.instructionsUpdatedAt, "an unchanged slot is not stamped")
        assertEquals(T0, resaved.createdAt)
        assertEquals(T0 + 1000, resaved.updatedAt)
    }

    @Test
    fun `a rebind grows the history, an unbind keeps it, and an unknown slot is refused by name`() {
        val s = store()
        val id = s.upsert(team(lead, builder)).id
        s.bind(id, mapOf("b1" to S1))
        s.bind(id, mapOf("b1" to S2))
        val unbound = s.bind(id, mapOf("b1" to null))
        val b1 = unbound.slots.single { it.id == "b1" }
        assertNull(b1.session)
        assertEquals(listOf(S1, S2), b1.sessionsHistory)
        s.bind(id, mapOf("b1" to S1))
        val history = s.team(id)?.slots?.single { it.id == "b1" }?.sessionsHistory
        assertEquals(listOf(S1, S2), history, "a session is listed once")
        val refused = assertThrows(TeamRefusal::class.java) { s.bind(id, mapOf("nope" to S1)) }
        assertEquals("no such slot in $id: nope", refused.message)
        assertEquals(listOf(s.team(id)!! to s.team(id)!!.slots.single { it.id == "b1" }), s.bindingsOf(S1))
    }

    @Test
    fun `instructions are set, blank clears them, and archive only sets the flag`() {
        val s = store()
        val id = s.upsert(team(lead, builder)).id
        assertEquals("be terse", s.instruct(id, "b1", "be terse").slots.single { it.id == "b1" }.instructions)
        assertNull(s.instruct(id, "b1", "  ").slots.single { it.id == "b1" }.instructions)
        val archived = s.archive(id)
        assertTrue(archived.archived)
        assertEquals(listOf(archived), s.teams(), "archived teams stay listed; nothing is deleted")
        assertEquals("no such team: gone", assertThrows(TeamRefusal::class.java) { s.archive("gone") }.message)
    }

    @Test
    fun `a composition the store cannot hold is refused with the reason`() {
        val s = store()
        val reasons = listOf(
            team(lead).copy(name = " ") to "a team needs a name",
            team(lead, builder.copy(id = "")) to "every slot needs an id",
            team(lead, lead.copy(role = "again")) to "slot ids repeat in atlas",
            team(lead, builder.copy(head = "")) to "every slot needs a head",
        )
        for ((bad, reason) in reasons) {
            assertEquals(reason, assertThrows(TeamRefusal::class.java) { s.upsert(bad) }.message)
        }
        assertFalse(Files.exists(file), "a refused write writes nothing")
    }

    @Test
    fun `a keyed create replays its own body across a restart, even after a bind, and keeps its key`() {
        val (made, fresh) = store().create(team(lead), "k-1")
        assertTrue(fresh)
        assertEquals("k-1", made.idempotencyKey)
        store().bind(made.id, mapOf("lead" to S1))
        val (again, freshAgain) = store().create(team(lead), "k-1")
        assertFalse(freshAgain, "a retry of the same body is not a second team, whatever bound since")
        assertEquals(made.id, again.id)
        assertEquals(1, store().teams().size)
        val blank = assertThrows(TeamRefusal::class.java) { store().create(team(lead), " ") }
        assertEquals("a create needs an idempotency key", blank.message)
        val replaced = store().upsert(team(lead, id = made.id).copy(idempotencyKey = null, createFingerprint = null))
        assertEquals("k-1", replaced.idempotencyKey, "a replace keeps the key")
        assertEquals(made.createFingerprint, replaced.createFingerprint, "and the fingerprint of the create")
    }

    @Test
    fun `a reused key with a different body is refused, and a team made before fingerprints replays`() {
        val (made, _) = store().create(team(lead), "k-1")
        val conflict = assertThrows(TeamKeyConflict::class.java) { store().create(team(lead, builder), "k-1") }
        assertEquals(
            "idempotency key k-1 already made ${made.id} from a different body; a new team needs a new key",
            conflict.message,
        )
        assertEquals(1, store().teams().size, "the edited composition was not silently dropped into the old team")
        val printed = Regex("\"create_fingerprint\": \"[0-9a-f]+\"")
        Files.writeString(file, Files.readString(file).replace(printed, "\"create_fingerprint\": null"))
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000))
        assertEquals(made.id to false, store().create(team(lead, builder), "k-1").let { it.first.id to it.second })
    }

    @Test
    fun `a file that does not parse is never replaced, and every write names it`() {
        Files.writeString(file, "{ not json")
        val s = store()
        assertEquals(emptyList<Team>(), s.teams())
        val refused = assertThrows(TeamRefusal::class.java) { s.upsert(team(lead)) }
        assertEquals("$file does not parse; fix or move it first", refused.message)
        assertEquals("{ not json", Files.readString(file), "the operator's file is untouched")
    }

    @Test
    fun `each write backs up the version it replaces, and a hand edit is read without a restart`() {
        val s = store()
        val first = s.upsert(team(lead))
        val before = Files.readString(file)
        s.archive(first.id)
        assertEquals(before, Files.readString(dir.resolve("teams.json.bak")))
        Files.writeString(file, Files.readString(file).replace("\"atlas\"", "\"renamed\""))
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000))
        assertEquals("renamed", s.teams().single().name)
    }
}
