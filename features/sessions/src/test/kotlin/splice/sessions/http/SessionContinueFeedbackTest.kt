// NEW: Oct 27, 2026 — a relaunch and a Continue on leave a session holding a feedback draft the person has not sent
// where it is, and type nothing into it. Claude Code 2.1.310 answers /exit in such a session with "You have 1 unsent
// feedback draft · Enter to review & send · Esc to discard and exit", and sending or discarding the draft is the
// person's call. On Oct 27 at 08:30 CT a relaunch typed /exit into one in ~/.claude-claudex: its client sat at that
// question, and the relaunch gave up after 15 s and left it there.
//
// A session's drafts are files in its config folder, <folder>/feedback/drafts/<draft_id>.json, each naming the session
// it came from in source_session_id, with "queued" in its status until it is sent. A head's seats share that folder.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.session.SessionKey
import splice.core.session.SessionPane
import splice.sessions.registry.FeedbackDrafts
import splice.sessions.registry.SessionRecord
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.random.Random

/** What a refusal for a feedback draft says, in its sentence. */
private const val HOLDS_FEEDBACK = "holds a feedback draft that has not been sent"

class SessionContinueFeedbackTest {
    @TempDir
    lateinit var tmp: Path

    private val rig by lazy { TeamRig(tmp) }
    private val terminal = FakeTerminal()
    private val panes = FakePanes()
    private val leadPane = SessionPane("%lead")

    /** The sessions a move or a relaunch brought back, as its ledger is told. */
    private val arrived = mutableListOf<String>()

    /** The config folder every session's client runs in, as the daemon reads it from its process: one folder, as the
     *  seats of one head share ~/.claude-claudex. */
    private var configFolder: Path? = null

    /** What the registry reads once a stop the move pressed lands: idle at its prompt, unless a test says otherwise. */
    private var stoppedTo: () -> Unit = { leadIdle() }

    /** JSON written over many lines, as a writer that indents it leaves it. */
    private val spread = Json { prettyPrint = true }

    private val continueOn by lazy {
        SessionContinue(
            drive = SessionDrive(
                TerminalSource { SessionDriver(terminal, panes) },
                feedback = FeedbackDrafts { configFolder },
            ),
            commands = StartCommands { head -> StartCommand.Ready(listOf("claude-$head")) },
            handover = object : SessionHandover {
                // a terminal splice opened closes with its client
                override suspend fun left(session: String, pid: Long, seconds: Long): Boolean =
                    true.also { terminal.live = false }

                override suspend fun stopped(session: String, seconds: Long): Boolean = true.also { stoppedTo() }

                override suspend fun cameOn(session: String, head: String, seconds: Long): Boolean = true
            },
            registry = rig.registry,
            home = tmp.resolve("home"),
            ledger = object : MoveLedger {
                private val open = mutableSetOf<String>()

                override fun opens(session: String): Boolean = open.add(session)

                override fun leaving(record: SessionRecord, to: String, model: String?) = Unit

                override fun arrived(session: String, arrivedAs: String) {
                    arrived += arrivedAs
                }

                override fun failed(session: String): String? = null

                override fun dropped(session: String) {
                    open.remove(session)
                }
            },
            relaunches = object : SessionRelaunches {
                override fun model(record: SessionRecord): String? = "claude-fable-5-1"

                override fun command(record: SessionRecord, model: String?): StartCommand =
                    StartCommand.Ready(listOf("/home/p/.local/bin/claude", "-r", LEAD, "--model", "claude-fable-5-1"))
            },
        )
    }

    private fun move(session: String, body: String) = runBlocking { continueOn.moveJson(session, body) }

    private fun relaunch(session: String) = runBlocking { continueOn.relaunch(session) }

    /** The lead's client reports a turn in flight, as Claude Code writes it to its registry file. */
    private fun leadWorking() {
        val file = rig.tmp.resolve("sessions/1.json")
        Files.writeString(file, Files.readString(file).replace("{", """{"status":"busy","""))
    }

    private fun leadIdle() {
        val file = rig.tmp.resolve("sessions/1.json")
        Files.writeString(file, Files.readString(file).replace(""""status":"busy",""", ""))
    }

    /** A feedback draft as Claude Code 2.1.310 writes one: from [session], "queued" in [status] until it is sent. */
    private fun draftText(session: String, status: String): String =
        """{"type":"bug","title":"a tool call failed","details":"the edit did not apply",""" +
            """"source_session_id":"$session","status":"$status","created_at":"2026-10-27T13:30:00.000Z",""" +
            """"draft_id":"${UUID.randomUUID()}","trigger":"model_judgment","cli_version":"2.1.310"}"""

    /** [text] kept as Claude Code keeps a draft, <the config folder>/feedback/drafts/<draft_id>.json. */
    private fun keepDraft(text: String) {
        val drafts = Files.createDirectories(checkNotNull(configFolder).resolve("feedback/drafts"))
        Files.writeString(drafts.resolve("${UUID.randomUUID()}.json"), text)
    }

    /** The lead's own queued draft as a disk or a writer could spoil it, so that it no longer is one: cut off before
     *  its closing brace, not an object, with no status, naming no session, or with a status that is not a word. */
    private val spoiled: List<(String, Random) -> String> = listOf(
        { own, random -> own.take(random.nextInt(1, own.length)) },
        { own, _ -> "[$own]" },
        { own, _ -> own.replace(""""status":"queued",""", "") },
        { own, _ -> own.replace(""""source_session_id":"$LEAD"""", """"source_session_id":null""") },
        { own, _ -> own.replace(""""status":"queued",""", """"status":["queued"],""") },
        { _, _ -> "" },
    )

    /** One file Claude Code could leave among a folder's drafts, drawn by [random], and whether it holds the lead: a
     *  whole JSON object naming the lead in its source_session_id, with "queued" in its status. */
    private fun anyDraftFile(random: Random): Pair<String, Boolean> {
        if (random.nextBoolean()) return spoiled.random(random)(draftText(LEAD, "queued"), random) to false
        val others = listOf(BUILDER, OUTSIDER, UUID.randomUUID().toString())
        val session = if (random.nextBoolean()) LEAD else others.random(random)
        val status = if (random.nextBoolean()) "queued" else listOf("sent", "discarded", "Queued", "").random(random)
        val whole = draftText(session, status)
        val text = if (random.nextBoolean()) whole else spread.encodeToString(JsonObject.serializer(), rig.json(whole))
        return text to (session == LEAD && status == "queued")
    }

    // Continue on ends the client with the same /exit, so it leaves such a session where it is: idle, with its prompt
    // to be emptied on the person's say-so, or in a turn, which it does not stop for a move it cannot make.
    @Test
    fun `Continue on leaves a session holding an unsent feedback draft where it is, and types nothing`() {
        panes.remember(LEAD, leadPane)
        configFolder = tmp.resolve("home/.claude-claudex")
        keepDraft(draftText(LEAD, "queued"))

        val idle = move(LEAD, """{"head":"codex","model":"gpt-6-sol"}""")
        val cleared = move(LEAD, """{"head":"codex","clear":true}""")
        leadWorking()
        val working = move(LEAD, """{"head":"codex"}""")

        for (reply in listOf(idle, cleared, working)) {
            assertEquals(HttpStatusCode.Conflict, reply.status, reply.body)
            val body = rig.json(reply.body)
            assertEquals("feedback", body.getValue("reason").jsonPrimitive.content, "$body")
            assertTrue(HOLDS_FEEDBACK in body.getValue("error").jsonPrimitive.content, "it says why: $body")
        }
        assertTrue(terminal.sent.isEmpty() && terminal.pressed.isEmpty(), "nothing is typed or pressed, not even Stop")
        assertTrue(terminal.opened.isEmpty() && arrived.isEmpty(), "nothing is started")
    }

    // The model drafts feedback during a turn. A move stops the turn it was pressed on, and a draft written in that
    // turn is there once it has stopped, so /exit would land in the question.
    @Test
    fun `a stopped turn that leaves a feedback draft gets no exit, and the session stays where it is`() {
        panes.remember(LEAD, leadPane)
        configFolder = tmp.resolve("home/.claude-claudex")
        leadWorking()
        stoppedTo = {
            leadIdle()
            keepDraft(draftText(LEAD, "queued"))
        }

        val reply = move(LEAD, """{"head":"codex"}""")

        assertEquals(HttpStatusCode.Conflict, reply.status, reply.body)
        val body = rig.json(reply.body)
        assertEquals("feedback", body.getValue("reason").jsonPrimitive.content, "$body")
        assertTrue(HOLDS_FEEDBACK in body.getValue("error").jsonPrimitive.content, "it says why: $body")
        assertEquals(listOf(SessionKey.STOP), terminal.pressed.map { it.second }, "only its turn stops")
        assertTrue(terminal.sent.isEmpty(), "no /exit lands in the feedback question")
        assertTrue(terminal.opened.isEmpty() && arrived.isEmpty(), "nothing is started")
    }

    // A head's seats share its config folder, and it holds whatever Claude Code left there: every seat's drafts, drafts
    // already sent or discarded, and on Oct 27 one file cut off mid-way. Only a draft of the session's own that has not
    // been sent holds it: every other file, whatever it holds, leaves its relaunch free to go on.
    @Test
    fun `only a draft of its own that has not been sent holds a relaunch, whatever else its folder holds`() {
        panes.remember(LEAD, leadPane)
        terminal.holds = false // a relaunch past its checks stops at the pane, before anything is typed into it
        val random = Random(1027)
        var held = 0
        repeat(200) { run ->
            configFolder = Files.createDirectories(tmp.resolve("folders/$run"))
            val files = List(random.nextInt(0, 6)) { anyDraftFile(random) }
            files.forEach { (text, _) -> keepDraft(text) }
            val holds = files.any { (_, own) -> own }

            val reply = relaunch(LEAD)

            val shown = "${files.map { it.first }}: ${reply.body}"
            if (holds) {
                held++
                assertEquals(HttpStatusCode.Conflict, reply.status, shown)
                assertEquals("feedback", rig.json(reply.body).getValue("reason").jsonPrimitive.content, shown)
                assertTrue(HOLDS_FEEDBACK in rig.json(reply.body).getValue("error").jsonPrimitive.content, shown)
            } else {
                assertEquals(HttpStatusCode.BadGateway, reply.status, "it went on, to its pane: $shown")
            }
        }
        assertTrue(held in 1 until 200, "the folders drawn hold the lead and leave it alike: $held of 200 held it")
        assertTrue(terminal.sent.isEmpty() && terminal.pressed.isEmpty(), "nothing is typed into its pane")
    }
}
