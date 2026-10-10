// NEW: Oct 10, 2026 — driving one session from Sessions by its id: what reaches its terminal, and what is refused.
//
// The behaviour a person relies on: what he writes reaches THAT session whole, his answer presses the choice he
// picked, Stop presses stop and nothing else, and a session splice did not open is never typed into blind.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.session.SessionKey
import splice.core.session.SessionPane

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
        }
        assertTrue(terminal.sent.isEmpty() && terminal.pressed.isEmpty(), "a pane splice did not open is untouched")
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
        assertEquals(HttpStatusCode.Conflict, drive.stop(OURS).status)

        terminal.live = true
        terminal.sendFails = true
        val failed = drive.sayJson(OURS, """{"text": "hello"}""")
        assertEquals(HttpStatusCode.BadGateway, failed.status)
        assertTrue(failed.body.contains("the terminal did not take it"), failed.body)

        wired = false
        assertEquals(HttpStatusCode.ServiceUnavailable, drive.sayJson(OURS, """{"text": "hello"}""").status)
    }

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

        terminal.live = false
        val gone = launchedDrive.stop(LAUNCHED)
        assertEquals(HttpStatusCode.Conflict, gone.status)
        assertTrue(gone.body.contains("is gone"), gone.body)
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
