// Fix 3 (Oct 27): a person who installs splice and signs in to ChatGPT gets GPT in code mode from their first turn, as
// OpenAI's model list marks those models, instead of up to an hour of plain function tools. Setup restarts the daemon
// before it signs in, so the list asked at the daemon's start had no credential; these drive a real codex head through
// its HTTP routes against a loopback ChatGPT backend, with the daemon's roster beats held by the test.
package splice.app.roster

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.core.wire.HttpStatus
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val ACCOUNT_A = "synthetic-account-a"
private const val ACCOUNT_B = "synthetic-account-b"

/** A model only account B's plan lists. */
private const val B_ONLY_MODEL = "gpt-6-synthetic-b"

/** Code mode's one tool, as Codex names it. */
private const val EXEC = "exec"

@Timeout(90)
class SignInCodeModeTest {
    @Test
    fun `a head signed in after the daemon started runs code mode on the first turn after sign-in`(@TempDir tmp: Path) =
        runBlocking {
            val fixture = SignInRosterFixture(tmp, this)
            try {
                withTimeout(60.seconds) {
                    fixture.start()
                    val token = fixture.login.accessToken(ACCOUNT_A, "first")
                    fixture.backend.serve(token, listOf(listedModel(CODE_MODE_MODEL, codeModeOnly = true)))
                    fixture.login.signIn(ACCOUNT_A, "first")
                    fixture.credentialBeat.beat()

                    val turn = fixture.turn()
                    assertEquals(HttpStatus.OK, turn.statusCode(), turn.body())
                    assertTrue("message_stop" in turn.body(), turn.body())
                    val offered = offeredTools(fixture.backend.turns.first())
                    assertTrue(EXEC in offered, "the first turn after sign-in offered $offered, not code mode")
                }
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `another account's sign-in gets that account's list without waiting for the hourly refresh`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val fixture = SignInRosterFixture(tmp, this)
        try {
            withTimeout(60.seconds) {
                val first = fixture.login.accessToken(ACCOUNT_A, "a")
                fixture.backend.serve(first, listOf(listedModel(CODE_MODE_MODEL, codeModeOnly = false)))
                fixture.login.signIn(ACCOUNT_A, "a")
                fixture.start()
                assertEquals(HttpStatus.OK, fixture.turn().statusCode())
                assertFalse(EXEC in offeredTools(fixture.backend.turns.last()), "account A's list marks no code mode")
                assertFalse(B_ONLY_MODEL in fixture.picker().body(), fixture.picker().body())

                val second = fixture.login.accessToken(ACCOUNT_B, "b")
                fixture.backend.serve(
                    second,
                    listOf(listedModel(CODE_MODE_MODEL, codeModeOnly = true), listedModel(B_ONLY_MODEL, false)),
                )
                fixture.login.signIn(ACCOUNT_B, "b")
                fixture.credentialBeat.beat()

                val picker = fixture.picker()
                assertEquals(HttpStatus.OK, picker.statusCode(), picker.body())
                assertTrue(B_ONLY_MODEL in picker.body(), "account B's list reaches the picker: ${picker.body()}")
                val turn = fixture.turn()
                assertEquals(HttpStatus.OK, turn.statusCode(), turn.body())
                val offered = offeredTools(fixture.backend.turns.last())
                assertTrue(EXEC in offered, "account B's list marks code mode, and the turn offered $offered")
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a list that cannot be fetched after sign-in falls back as before, and the log says why once`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val fixture = SignInRosterFixture(tmp, this)
        try {
            withTimeout(60.seconds) {
                fixture.start()
                fixture.backend.listOutage = true
                val token = fixture.login.accessToken(ACCOUNT_A, "a")
                fixture.backend.serve(token, listOf(listedModel(CODE_MODE_MODEL, codeModeOnly = true)))
                fixture.login.signIn(ACCOUNT_A, "a")
                fixture.credentialBeat.beat()

                val why = "[$CODEX_HEAD] models: HTTP 500 from ${fixture.provider.rosterUrl}"
                assertEquals(1, fixture.daemonLog.count { it.startsWith(why) }, "${fixture.daemonLog}")
                val turn = fixture.turn()
                assertEquals(HttpStatus.OK, turn.statusCode(), turn.body())
                val offered = offeredTools(fixture.backend.turns.last())
                assertTrue("synthetic_tool" in offered && EXEC !in offered, "no list is known: direct tools, $offered")

                fixture.credentialBeat.beat()
                fixture.hourly.beat()
                assertEquals(1, fixture.daemonLog.count { it.startsWith(why) }, "${fixture.daemonLog}")
            }
        } finally {
            fixture.close()
        }
    }
}
