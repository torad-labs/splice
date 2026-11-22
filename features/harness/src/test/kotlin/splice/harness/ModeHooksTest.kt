// NEW: a mode's hooks at the door's moments (modes/README.md, "Hooks"). A session-start hook places its context and
// chooses the model once, and keeps both; a request hook changes the body on its way upstream; a hook that refuses
// refuses the turn; one that fails lets it go on unless its manifest says closed.
package splice.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.harness.hook.HookAnswer
import splice.harness.hook.HookCall
import splice.harness.hook.HookMoment
import splice.harness.hook.HookRun
import splice.harness.hook.HookSpec
import splice.harness.hook.HookWorker
import splice.harness.hook.ModeHookPort
import java.nio.file.Path

class ModeHooksTest(@param:TempDir private val tmp: Path) {
    private val rig = CapabilityRig(tmp)

    @AfterEach
    fun close() = rig.close()

    /** A port whose only hook is [answer] at [moment]. */
    private fun port(
        moment: HookMoment,
        closed: Boolean = false,
        answer: (HookCall) -> HookAnswer,
    ) =
        ModeHookPort { _ ->
            listOf(
                HookSpec(
                    "demo",
                    moment,
                    0,
                    TIMEOUT_MS,
                    closed,
                    HookWorker.InProcess(HookRun(answer)),
                ),
            )
        }

    @Test
    fun `a session-start hook places its context once and keeps it on every later turn`() = runBlocking {
        var runs = 0
        val hooks = port(HookMoment.SESSION_START) {
            runs++
            HookAnswer.AddContext("from the demo mode")
        }
        val head = rig.head(emptyList(), workers = RigWorkers(hooks = hooks))

        rig.send(head, Turn("s1", opening("Fix the bug")))
        rig.send(head, Turn("s1", later("Fix the bug", "go on")))

        assertEquals(1, runs, "the session-start hook ran once")
        rig.provider.forEach { body ->
            assertTrue(firstUserTexts(body).contains("from the demo mode"), body)
        }
    }

    @Test
    fun `a session-start hook chooses the model its session runs on`() = runBlocking {
        val hooks = port(HookMoment.SESSION_START) { HookAnswer.ChooseModel("astra-small") }
        val head = rig.head(emptyList(), workers = RigWorkers(hooks = hooks))

        rig.send(head, Turn("s1", opening("Fix the bug")))

        val body = Json.parseToJsonElement(rig.provider.single()).jsonObject
        assertEquals("astra-small", body.getValue("model").jsonPrimitive.content)
    }

    @Test
    fun `a request hook changes the body on its way to the provider`() = runBlocking {
        val hooks = port(HookMoment.REQUEST) { call ->
            HookAnswer.Rewrite(JsonObject(call.body + ("system" to JsonPrimitive("from the request hook"))))
        }
        val head = rig.head(emptyList(), workers = RigWorkers(hooks = hooks))

        rig.send(head, Turn("s1", opening("Fix the bug")))

        val body = Json.parseToJsonElement(rig.provider.single()).jsonObject
        assertEquals("from the request hook", body.getValue("system").jsonPrimitive.content)
    }

    @Test
    fun `a hook that refuses refuses the turn with its reason, and the provider gets nothing`() = runBlocking {
        val hooks = port(HookMoment.SESSION_START, closed = true) { HookAnswer.Refuse("not today") }
        val head = rig.head(emptyList(), workers = RigWorkers(hooks = hooks))

        val stream = rig.send(head, Turn("s1", opening("Fix the bug")))

        assertTrue(stream.contains("event: error"), stream)
        assertTrue(stream.contains("mode demo refused this turn: not today"), stream)
        assertEquals(emptyList<String>(), rig.provider)
    }

    @Test
    fun `a hook that throws fails open, and the turn goes on`() = runBlocking {
        val hooks = port(HookMoment.SESSION_START) { error("the hook broke") }
        val head = rig.head(emptyList(), workers = RigWorkers(hooks = hooks))

        rig.send(head, Turn("s1", opening("Fix the bug")))

        assertEquals(1, rig.provider.size, "the turn reached the provider")
    }

    @Test
    fun `a hook that throws fails closed, and the turn is refused`() = runBlocking {
        val hooks = port(HookMoment.SESSION_START, closed = true) { error("the hook broke") }
        val head = rig.head(emptyList(), workers = RigWorkers(hooks = hooks))

        val stream = rig.send(head, Turn("s1", opening("Fix the bug")))

        assertTrue(stream.contains("event: error"), stream)
        assertTrue(stream.contains("mode demo could not run its session_start hook"), stream)
        assertEquals(emptyList<String>(), rig.provider)
    }
}

private const val TIMEOUT_MS = 5_000L
