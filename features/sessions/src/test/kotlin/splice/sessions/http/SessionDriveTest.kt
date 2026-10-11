// NEW: Oct 10, 2026 — driving one session from Sessions by its id: what reaches its terminal, and what is refused.
//
// The behaviour a person relies on: what he writes reaches THAT session whole, his answer presses the choice he
// picked, Stop presses stop and nothing else, and a session splice did not open is never typed into blind.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.session.SessionKey
import splice.core.session.SessionPane
import splice.http.JsonReply

class SessionDriveTest {
    private val terminal = FakeTerminal()
    private val panes = FakePanes().apply { remember(OURS, SessionPane("%7")) }
    private var wired = true
    private val drive = SessionDrive(TerminalSource { SessionDriver(terminal, panes).takeIf { wired } })
    private val launchedDrive = SessionDrive(
        TerminalSource { SessionDriver(terminal, panes) },
        launched = LaunchedTerminals { id ->
            LaunchedTerminal(LAUNCHED_PID, "%3", "/tmp/tmux-1000/default").takeIf { id == LAUNCHED }
        },
    )

    @Test
    fun `a message reaches the session's own pane whole, line breaks and all`() {
        val reply = drive.sayJson(OURS, """{"text": "Totals should round like the PDF.\nAdd a test."}""")

        assertEquals(HttpStatusCode.OK, reply.status, reply.body)
        assertEquals(listOf(SessionPane("%7") to "Totals should round like the PDF.\nAdd a test."), terminal.sent)
    }

    @Test
    fun `an answer presses the numbered choice he picked, and stop presses stop`() {
        assertEquals(HttpStatusCode.OK, drive.answerJson(OURS, """{"choice": 2}""").status)
        assertEquals(HttpStatusCode.OK, drive.stop(OURS).status)

        val pane = SessionPane("%7")
        assertEquals(listOf(pane to SessionKey.CHOICE_2, pane to SessionKey.STOP), terminal.pressed)
    }

    @Test
    fun `nothing reaches a session splice did not open, and the reply says where to do it instead`() {
        val said = drive.sayJson(THEIRS, """{"text": "hello"}""")
        val answered = drive.answerJson(THEIRS, """{"choice": 1}""")
        val stopped = drive.stop(THEIRS)

        listOf(said, answered, stopped).forEach {
            assertEquals(HttpStatusCode.Conflict, it.status)
            assertTrue(it.body.contains("terminal it runs in"), it.body)
            assertEquals("not_ours", reason(it))
        }
        assertTrue(terminal.sent.isEmpty() && terminal.pressed.isEmpty(), "a pane splice did not open is untouched")
    }

    /** A Stop before any reply puts his message back in the prompt; a message typed after it would carry it too. */
    @Test
    fun `a message is never sent with words the prompt already holds, and is sent alone once he says clear`() {
        terminal.screenText = "────────────── tax-rounding ─\n❯ take your time with it\n──────────────\n"

        val held = drive.sayJson(OURS, """{"text": "Say hi."}""")
        assertEquals(HttpStatusCode.Conflict, held.status)
        assertEquals("draft", reason(held))
        assertTrue(held.body.contains("take your time with it"), held.body)
        assertTrue(terminal.sent.isEmpty(), "nothing is typed after his leftover words")

        val cleared = drive.sayJson(OURS, """{"text": "Say hi.", "clear": true}""")
        assertEquals(HttpStatusCode.OK, cleared.status, cleared.body)
        assertEquals(listOf(SessionPane("%7") to SessionKey.CLEAR), terminal.pressed)
        assertEquals(listOf(SessionPane("%7") to "Say hi."), terminal.sent)
    }

    @Test
    fun `an empty message and a choice that is not numbered are refused in words, and nothing is pressed`() {
        assertEquals(HttpStatusCode.BadRequest, drive.sayJson(OURS, """{"text": "   "}""").status)
        assertEquals(HttpStatusCode.BadRequest, drive.sayJson(OURS, "not json").status)
        val noChoice = drive.answerJson(OURS, """{}""")
        assertEquals(HttpStatusCode.BadRequest, noChoice.status)
        assertTrue(noChoice.body.contains("1 to 9"), noChoice.body)
        assertTrue(terminal.sent.isEmpty() && terminal.pressed.isEmpty())
    }

    @Test
    fun `a closed pane, no terminal, and a terminal that fails each answer in words`() {
        terminal.live = false
        val closed = drive.stop(OURS)
        assertEquals(HttpStatusCode.Conflict, closed.status)
        assertEquals("closed", reason(closed))

        terminal.live = true
        terminal.sendFails = true
        val failed = drive.sayJson(OURS, """{"text": "hello"}""")
        assertEquals(HttpStatusCode.BadGateway, failed.status)
        assertTrue(failed.body.contains("the terminal did not take it"), failed.body)
        assertEquals("refused", reason(failed))

        wired = false
        val unwired = drive.sayJson(OURS, """{"text": "hello"}""")
        assertEquals(HttpStatusCode.ServiceUnavailable, unwired.status)
        assertEquals("no_terminal", reason(unwired))
    }

    /** The refusal as the key a page names it by, never its sentence. */
    private fun reason(reply: JsonReply): String? =
        Json.parseToJsonElement(reply.body).jsonObject["reason"]?.jsonPrimitive?.content

    @Test
    fun `a session started in his own terminal is driven through the pane its launch recorded`() {
        val pane = SessionPane("%3@/tmp/tmux-1000/default")
        terminal.inFront[pane] = LAUNCHED_PID
        val reply = launchedDrive.sayJson(LAUNCHED, """{"text": "Add a test."}""")

        assertEquals(HttpStatusCode.OK, reply.status, reply.body)
        assertEquals(listOf(pane to "Add a test."), terminal.sent)
    }

    @Test
    fun `a recorded pane that now runs something else, or is closed, is refused by name and nothing is typed`() {
        terminal.inFront[SessionPane("%3@/tmp/tmux-1000/default")] = LAUNCHED_PID + 1
        val taken = launchedDrive.answerJson(LAUNCHED, """{"choice": 1}""")
        assertEquals(HttpStatusCode.Conflict, taken.status)
        assertTrue(taken.body.contains("running something else"), taken.body)
        assertEquals("pane_taken", reason(taken))

        terminal.live = false
        val gone = launchedDrive.stop(LAUNCHED)
        assertEquals(HttpStatusCode.Conflict, gone.status)
        assertTrue(gone.body.contains("is gone"), gone.body)
        assertEquals("pane_gone", reason(gone))
        assertTrue(terminal.sent.isEmpty() && terminal.pressed.isEmpty())
    }

    @Test
    fun `the screen offers the choices the client drew, numbered as answer takes them`() {
        terminal.screenText = SCREEN
        val reply = drive.screen(OURS)

        assertEquals(HttpStatusCode.OK, reply.status, reply.body)
        assertTrue(reply.body.contains("\"choice\":1") && reply.body.contains("Yes"), reply.body)
        assertEquals(HttpStatusCode.Conflict, drive.screen(THEIRS).status)
    }

    private companion object {
        const val OURS = "11111111-1111-4111-8111-111111111111"
        const val THEIRS = "22222222-2222-4222-8222-222222222222"
        const val LAUNCHED = "33333333-3333-4333-8333-333333333333"
        const val LAUNCHED_PID = 4242L
        val SCREEN = """
            Bash command

              npm test -- tax.spec.ts
              Run the tax tests

            Do you want to proceed?
            ❯ 1. Yes
              2. Yes, and don't ask again for npm test commands in ~/code/billing-api
              3. No, and tell Claude what to do differently (esc)
        """.trimIndent()
    }
}
