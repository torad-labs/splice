// NEW: V4-148 — a session id this host does not know is adopted, not answered with the spec's
// "session not found". Claude Code does not reinitialize on that signal: its tools stop working for
// the rest of the session while /mcp still shows the server connected, and only a relaunch recovers
// (claude-code #60949, #55970, #59442, #55228). These cells are the three things adoption must settle:
// a restart is invisible, an ended session stays ended, and an overflowed one still reinitializes.
//
// The fixture lives in the default package (McpHostFixture), which a campaign-packaged test could not
// import; the row is named in every cell instead.
package splice.control.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class McpSessionAdoptionTest : McpHostFixture() {

    // Mutant: answer the lookup miss with 404 again. The client that outlived the daemon loses every
    // tool for the rest of its session, with /mcp still showing the server connected.
    @Test
    fun `a session that outlived the host keeps working under the id the client already holds`(
        @TempDir dir: Path,
    ) = runBlocking {
        boot(dir)
        val session = init()
        assertTrue(text(call(session, 1, "echo", "before")).contains("echo=before"))
        val childBefore = text(call(session, 2, "echo")).substringBefore(" echo=")

        host.stop()
        boot(dir)

        assertTrue(text(call(session, 3, "echo", "after")).contains("echo=after"), "the same id still serves")
        assertFalse(
            text(call(session, 4, "echo")).startsWith(childBefore),
            "a new child answers it: the adoption is real, not a surviving process",
        )
    }

    // Mutant: drop the DELETE tombstone. An explicit end stops meaning anything, because the next
    // request with that id silently resurrects it.
    @Test
    fun `a session the client ended stays ended`(@TempDir dir: Path) = runBlocking {
        boot(dir)
        val session = init()
        assertTrue(host.endSession("fake", session))

        assertEquals(404, host.post("fake", session, MCP_HOST_LIST).status)
        assertEquals(404, host.post("fake", session, MCP_HOST_LIST).status, "still, on a second try")
    }

    // Mutant: drop the overflow tombstone in expire(). The sweep forgets the overflowed session, the
    // next request adopts its id, and the client keeps caching lists whose invalidations were dropped.
    @Test
    fun `an overflowed session reinitializes, before and after the sweep forgets it`(
        @TempDir dir: Path,
    ) = runBlocking {
        boot(dir)
        val session = init()
        call(session, 8, "overflow")
        assertEquals(404, host.post("fake", session, MCP_HOST_LIST).status)

        clock.now += 1
        host.sweep()

        assertEquals(404, host.post("fake", session, MCP_HOST_LIST).status, "an overflow is not adoptable")
    }

    // Mutant: keep the strict version check for adopted sessions. The 404 this row removes is traded
    // for a 400 on the client's very next request, and the client is just as broken.
    @Test
    fun `an adopted session speaks the version the client negotiated before the restart`(
        @TempDir dir: Path,
    ) = runBlocking {
        boot(dir)
        val session = init()
        host.stop()
        boot(dir)

        val reply = host.post("fake", session, MCP_HOST_LIST, protocolVersion = "2025-06-18")

        assertEquals(200, reply.status, reply.body)
        assertTrue(host.protocolAccepted("fake", session, "2025-06-18"))
        awaitLogged("adopted the client's session")
        assertTrue(
            synchronized(log) { log.toString() }.contains("negotiated 2025-11-25 where the client still names"),
            "the version difference is logged once, where the session is adopted",
        )
    }

    // The status surface must count an adopted session like any other: one session on one child.
    @Test
    fun `an adopted session is one session on the server's own count`(@TempDir dir: Path) = runBlocking {
        boot(dir)
        val session = init()
        host.stop()
        boot(dir)
        call(session, 5, "echo")

        assertEquals(1, status("fake")["sessions"]!!.jsonPrimitive.content.toInt())
    }
}
