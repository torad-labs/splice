// Unit and catalog tests for modes/ccdsession (ccd-session feature).
package splice.modes.ccdsession

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall
import splice.modes.api.ModeCaller
import splice.modes.api.ModeCatalogReader
import splice.modes.api.ModeRequest
import splice.modes.api.ModeSession
import splice.modes.api.ModeStore
import java.nio.file.Path

internal class CcdSessionModeTest {
    private class MemoryStore : ModeStore {
        private val data = mutableMapOf<String, String>()
        override fun read(session: String): String? = data[session]
        override fun write(session: String, text: String) {
            data[session] = text
        }
    }

    private fun session(id: String = "sess-1") = ModeSession(
        id = id,
        folder = "/tmp",
        head = "codex",
        model = "gpt-5.3-codex",
        caller = ModeCaller.DOOR,
    )

    private fun spawn(mode: CcdSessionMode, store: ModeStore, sid: String, title: String, cwd: String? = null): String {
        val input = buildJsonObject {
            put("title", title)
            put("tldr", "Summary for $title")
            put("prompt", "Prompt for $title")
            cwd?.let { put("cwd", it) }
        }
        val ans = mode.answer(ModeCall("spawn_task", input, session(sid), store)) as ModeAnswer.Text
        return Regex("task_[0-9a-f]{8}").find(ans.text)?.value ?: error("missing task id: ${ans.text}")
    }

    @Test
    fun `catalog loads ccdsession whole with Desktop caller off and exact tool names`() {
        val catalog = ModeCatalogReader(CcdSessionModeTest::class.java.classLoader).read()
        assertEquals(emptyList<String>(), catalog.findings, "findings: ${catalog.findings}")
        val mode = catalog.mode("ccdsession")
        assertTrue(mode != null, "ccdsession mode must be discovered")
        assertEquals("ccd_session", mode!!.manifest.server)
        assertTrue(mode.manifest.on)
        assertEquals(listOf("desktop"), mode.manifest.callersOff)
        assertEquals(
            listOf(
                "mcp__ccd_session__spawn_task",
                "mcp__ccd_session__dismiss_task",
                "mcp__ccd_session__mark_chapter",
            ),
            mode.tools.map { it.offeredAs },
        )
    }

    @Test
    fun `spawned tasks persist across restart and reject duplicate claims`(@TempDir tmp: Path) {
        val store = MemoryStore()
        val taskId = spawn(CcdSessionMode(), store, "sess-a", "Fix stale docs", tmp.toAbsolutePath().toString())
        val restarted = CcdSessionMode()

        val listRoute = restarted.routes.first { it.method == "GET" && it.path == "/api/suggested-tasks" }
        val listed = listRoute.handler.handle(ModeRequest("GET", "/api/suggested-tasks", emptyMap(), null, store))
        assertEquals(HttpStatus.OK, listed.status)
        val tasks = (listed.body.jsonObject["tasks"] as JsonArray).map { it.jsonObject }
        assertEquals(listOf(taskId), tasks.map { JsonScalars.str(it, "task_id") })

        val claimRoute = restarted.routes.first { it.path.endsWith("/claim") }
        val c1 = claimRoute.handler.handle(
            ModeRequest(
                method = "POST",
                path = "/api/suggested-tasks/$taskId/claim",
                query = emptyMap(),
                body = buildJsonObject { put("surface", "console") },
                store = store,
            ),
        )
        assertEquals(HttpStatus.OK, c1.status)
        val c2 = claimRoute.handler.handle(
            ModeRequest(
                method = "POST",
                path = "/api/suggested-tasks/$taskId/claim",
                query = emptyMap(),
                body = buildJsonObject { put("surface", "phone") },
                store = store,
            ),
        )
        assertEquals(HttpStatus.CONFLICT, c2.status)
        assertEquals("in_flight", JsonScalars.str(c2.body.jsonObject, "error"))
    }

    private fun post(
        route: splice.modes.api.ModeRoute,
        path: String,
        body: kotlinx.serialization.json.JsonObject,
        store: ModeStore,
    ) = route.handler.handle(ModeRequest("POST", path, emptyMap(), body, store))

    @Test
    fun `dismiss respects in-flight, release restores pending, and resolve records started session`() {
        val store = MemoryStore()
        val mode = CcdSessionMode()
        val taskId = spawn(mode, store, "sess-b", "Add unit tests")
        val claim = mode.routes.first { it.path.endsWith("/claim") }
        val resolve = mode.routes.first { it.path.endsWith("/resolve") }

        post(claim, "/api/suggested-tasks/$taskId/claim", buildJsonObject { put("surface", "console") }, store)
        val dismissAns = mode.answer(
            ModeCall("dismiss_task", buildJsonObject { put("task_id", taskId) }, session("sess-b"), store),
        ) as ModeAnswer.Text
        assertTrue("currently being started" in dismissAns.text, dismissAns.text)

        val rel = post(
            resolve,
            "/api/suggested-tasks/$taskId/resolve",
            buildJsonObject { put("outcome", "released") },
            store,
        )
        assertEquals("pending", JsonScalars.str(rel.body.jsonObject["task"]!!.jsonObject, "state"))

        val started = post(
            resolve,
            "/api/suggested-tasks/$taskId/resolve",
            buildJsonObject {
                put("outcome", "started")
                put("session_id", "sess-child-1")
            },
            store,
        )
        val row = started.body.jsonObject["task"]!!.jsonObject
        assertEquals("started", JsonScalars.str(row, "state"))
        assertEquals("sess-child-1", JsonScalars.str(row, "started_session_id"))

        val late = post(
            claim,
            "/api/suggested-tasks/$taskId/claim",
            buildJsonObject { put("surface", "console") },
            store,
        )
        assertEquals(HttpStatus.CONFLICT, late.status)
        assertEquals("not_pending", JsonScalars.str(late.body.jsonObject, "error"))
    }

    @Test
    fun `queue evicts oldest pending past 20 and mark_chapter records chapters`() {
        val store = MemoryStore()
        val mode = CcdSessionMode()
        for (i in 1..22) {
            spawn(mode, store, "sess-cap", "Task $i")
        }
        val listRoute = mode.routes.first { it.path == "/api/suggested-tasks" }
        val listed = listRoute.handler.handle(
            ModeRequest("GET", "/api/suggested-tasks", mapOf("session" to "sess-cap"), null, store),
        )
        val tasks = (listed.body.jsonObject["tasks"] as JsonArray).map { it.jsonObject }
        assertEquals(20, tasks.count { JsonScalars.str(it, "state") == "pending" })
        assertEquals(2, tasks.count { JsonScalars.str(it, "state") == "dismissed" })

        val chapAns = mode.answer(
            ModeCall(
                tool = "mark_chapter",
                input = buildJsonObject {
                    put("title", "Test verification")
                    put("summary", "Running integration tests")
                },
                session = session("sess-cap"),
                store = store,
            ),
        ) as ModeAnswer.Text
        assertTrue("Test verification" in chapAns.text, chapAns.text)

        val chapRoute = mode.routes.first { it.path == "/api/chapters" }
        val chapRes = chapRoute.handler.handle(
            ModeRequest("GET", "/api/chapters", mapOf("session" to "sess-cap"), null, store),
        )
        val chaps = (chapRes.body.jsonObject["chapters"] as JsonArray).map { it.jsonObject }
        assertEquals(1, chaps.size)
        assertEquals("Test verification", JsonScalars.str(chaps[0], "title"))
    }
}
