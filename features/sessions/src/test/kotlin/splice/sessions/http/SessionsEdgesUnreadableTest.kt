// NEW: Oct 10, 2026 — an edge store that cannot be read must not take the whole session list down.
//
// Found on the live daemon, three hours after the same class of bug was fixed one layer over
// (cac62c805, the resumable hint). `GET /api/sessions` answered 500 with the withheld-message error on
// every poll from 11:42 AM CT, so every console page that names a session fell back to its id and
// Requests read "Session 8cb8a71d" on every row. Attaching a recorder to the running daemon named the
// throw: `retained message-edge metadata exceeds its 16777216 byte allowance`, from
// MessageEdgeCache.ensure, reached through the `edges` summary the listing puts on each row.
//
// THE ALLOWANCE WAS RIGHT TO REFUSE. It exists so a runaway writer cannot take the daemon's heap, and
// a desk that has been running for months reaches it and never comes back, because the day files only
// grow. What was wrong is where the refusal landed: the edges are a SUMMARY ON a row, and the listing
// IS the page. So the read answers rather than throws, the rows go out without their edges, and the
// page says why — beside the store's own state, which stays true, because a store that is on and could
// not be read is two facts and not one.
//
// The failure driven here is a day file the daemon cannot open, which is a real reader's own
// IOException and needs no 16 MiB to produce. The allowance, a day that went away mid-walk and a
// directory that cannot be entered all arrive at this same escape.
package splice.sessions.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.AsyncFileIo
import splice.sessions.activity.ActivityStores
import splice.sessions.activity.MessageEdge
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class SessionsEdgesUnreadableTest {
    @TempDir lateinit var root: Path

    /** The day file the one recorded edge was written into. */
    private fun dayFile(): Path = root.resolve("activity/edges-2026-09-18.jsonl")

    private fun listing(rig: TeamRig, stores: ActivityStores): JsonObject {
        val source = ActivitySource { stores }
        val sessions = SessionsRoutes(rig.registry, TestTranscripts(), activity = source)
        return Json.parseToJsonElement(sessions.sessionsJson()).jsonObject
    }

    private fun rows(listing: JsonObject) = listing.getValue("sessions").jsonArray.map { it.jsonObject }

    /** THE WALL. The same store, the same sessions, read twice: once readable and once not. The page
     *  survives both, and only the edges summary goes away. */
    @Test
    fun `an unreadable edge store costs the rows their edges and not the listing`() {
        val rig = TeamRig(root)
        val stores = rig.stores
        stores.edges.record(MessageEdge(LEAD, BUILDER, AT, "toolu_unreadable"))
        assertTrue(AsyncFileIo.drain())

        val readable = listing(rig, stores)
        val readableRows = rows(readable)
        assertTrue(readableRows.isNotEmpty(), "the fixture has sessions to list")
        assertEquals("on", readable.getValue("edges_state").jsonPrimitive.content)
        assertNull(readable["edges_reason"], "a store that answered has nothing to explain")
        assertNotNull(readableRows.first()["edges"], "and its rows carry their edges summary")

        Files.setPosixFilePermissions(dayFile(), PosixFilePermissions.fromString("---------"))
        try {
            assumeFalse(Files.isReadable(dayFile()), "root reads whatever the mode")

            val refused = listing(rig, stores)

            assertEquals(
                readableRows.map { it.getValue("session_id") },
                rows(refused).map { it.getValue("session_id") },
                "every session still listed: the edges are a hint on a row, the listing is the page",
            )
            assertTrue(
                rows(refused).none { it.containsKey("edges") },
                "and no row claims edges it could not read",
            )
            assertEquals(
                "on",
                refused.getValue("edges_state").jsonPrimitive.content,
                "the store itself is still on, which is a different fact from this read failing",
            )
            assertTrue(
                refused.getValue("edges_reason").jsonPrimitive.content.startsWith(
                    "the message edges could not be read: ",
                ),
                "and the page says why, rather than the daemon's withheld sentence on every row",
            )
        } finally {
            Files.setPosixFilePermissions(dayFile(), PosixFilePermissions.fromString("rw-------"))
        }
    }
}
