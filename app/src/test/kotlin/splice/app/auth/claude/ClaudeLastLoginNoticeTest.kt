// NEW: the daemon's own word about a command's last login (LoginAlerting), heard the way the operator hears it: through
// the program they set, run for real, over the real pool and the roster /api/accounts serves.
package splice.app.auth.claude

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.LoginAlerting
import splice.core.config.LAUNCH_PROOF_HEADER
import splice.core.usage.ModelQuota
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.WallClock
import splice.head.usage.QuotaTracker
import splice.usage.alerts.AlertCommand
import splice.usage.alerts.AlertDelivery
import splice.usage.alerts.AlertStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class ClaudeLastLoginNoticeTest {
    @TempDir
    lateinit var home: Path

    /** One turn, so the pool knows which model the command's sessions ask for. */
    private suspend fun turn(client: HttpClient, rig: ClaudeNativePoolFixture.Rig) {
        val response = client.post("http://127.0.0.1:${rig.head.head.port}/v1/messages") {
            bearerAuth("synthetic-caller")
            header(LAUNCH_PROOF_HEADER, rig.proof)
            header("x-claude-code-session-id", "working")
            contentType(ContentType.Application.Json)
            setBody(
                """{"model":"synthetic-model","stream":false,"max_tokens":32,""" +
                    """"messages":[{"role":"user","content":"synthetic session turn"}]}""",
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    @Test
    fun `a command on its last login near a limit says so once through the operator's own command`() = runBlocking {
        // Claude Code's own login has used up the week of the model the sessions ask for, which its provider names
        // "Synthetic". The added login is the last that can serve it, at 88% of that week.
        val reset = System.currentTimeMillis() / 1_000L + 86_400L
        val week = QuotaWindow(40.0, reset, 604_800L)
        val reading = QuotaSnapshot(sevenDay = week, updatedAt = System.currentTimeMillis())
        val fixture = ClaudeNativePoolFixture(home).also {
            it.seed("native", quota = reading.copy(models = listOf(ModelQuota("Synthetic", 100.0, reset))))
            it.addLogin()
        }
        val folders = ClaudeAccountFolders(fixture.paths.stateDir, now = WallClock { 1000 })
        QuotaTracker(folders.accounts(NATIVE_HEAD).single().directory.resolve("quota.json"))
            .record(reading.copy(models = listOf(ModelQuota("Synthetic", 88.0, reset))))
        val upstream = fixture.nativeUpstream(CopyOnWriteArrayList(), CopyOnWriteArrayList()).start()
        val port = upstream.engine.resolvedConnectors().single().port
        val rig = fixture.rig(upstreamUrl = "http://127.0.0.1:$port")
        val heard = home.resolve("heard.txt")
        try {
            HttpClient(Java).use { client -> turn(client, rig) }
            val alerting = LoginAlerting(
                rig.server.accountRoster(),
                // a program of the operator's: it gets the message on standard input, one message a line here
                AlertCommand(listOf("/bin/sh", "-c", "cat >> \"$0\"; echo >> \"$0\"", heard.toString())),
                AlertDelivery(AlertStore(home.resolve("alerts.json")), {}, this),
                home.resolve("login-alerts.json"),
                {},
            )
            alerting.check()
            alerting.check()
            val lines = Files.readAllLines(heard)
            assertEquals(2, lines.size, "one line when the command is first set, then the notice once: $lines")
            assertTrue(lines[0].startsWith("splice will tell you here"), lines[0])
            assertTrue(lines[1].startsWith("$NATIVE_HEAD is on its last login that can serve Synthetic: "), lines[1])
            assertTrue(lines[1].contains("added has used 88% of its Synthetic week, which resets "), lines[1])
            assertTrue(lines[1].endsWith("When it runs out, sessions asking for Synthetic wait."), lines[1])
        } finally {
            rig.close()
            upstream.stop()
        }
    }
}
