package splice.app.control

import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.auth.claude.ClaudePoolChanges
import splice.app.auth.claude.LoginJoined
import splice.app.provider.WiredAccount
import splice.app.provider.WiredAccountQuota
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.perf.OutcomeTag
import splice.sessions.http.SessionEnding
import splice.sessions.http.SessionEndingOf
import splice.sessions.note.NoteOutcome
import splice.sessions.note.SessionNoteSender
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.sessions.registry.SessionStatus
import splice.upstream.LifecycleScope
import java.nio.file.Path

class LimitResumeTest {
    private class Auth : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials = Credentials.Bearer("t")
        override suspend fun refresh(): Credentials = credentials()
        override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
    }

    private fun session(id: String, head: String) = SessionRecord(
        sessionId = id,
        name = null,
        status = SessionStatus("idle"),
        route = SessionRoute.Head(head),
        availability = SessionAvailability.LIVE,
        process = SessionProcess(pid = 1, cwd = null, startedAt = null, updatedAt = 1, messagingSocketPath = "/s/$id"),
        client = SessionClient(kind = null, version = "2.1.285"),
    )

    private fun account(label: String, dir: Path) =
        WiredAccount(label, label == "one", Auth(), WiredAccountQuota(dir.resolve("$label.json")))

    @Test
    fun `adding an account releases the three sessions the limit stopped, once, and no other`(@TempDir dir: Path) {
        val limited = listOf("a", "b", "c").map { session(it, "claude") }
        val others = listOf(
            session("stopped", "claude"),
            session("elsewhere", "claudex"),
            session("outage", "claude"),
            session("clean", "claude"),
        )
        val endings = mapOf(
            "a" to ending(OutcomeTag.PLAN_LIMIT, "claude"),
            "b" to ending(OutcomeTag.ALL_ACCOUNTS_EXHAUSTED, "claude"),
            "c" to ending(OutcomeTag.PLAN_LIMIT, "claude"),
            "stopped" to ending(OutcomeTag.PLAN_LIMIT, "claude"),
            "elsewhere" to ending(OutcomeTag.PLAN_LIMIT, "claudex"),
            "outage" to ending(OutcomeTag.UPSTREAM_FAILED, "claude"),
        )
        val sent = mutableListOf<Pair<String?, String>>()
        val logged = mutableListOf<String>()
        val resume = LimitResume(
            object : SessionSource {
                override fun read() = limited + others

                override fun list() = SessionListing(limited + others)
            },
            SessionEndingOf { endings[it] },
            SessionNoteSender { target, text ->
                sent += target.sessionId to text
                if (target.sessionId == "c") NoteOutcome.Failed("no messaging socket") else NoteOutcome.Submitted("m")
            },
            LifecycleScope(Dispatchers.Unconfined),
            { logged += it },
            clock = { NOW },
        )
        resume.acted("stopped")
        val pool = ClaudePoolChanges().also { it.onJoined = LoginJoined(resume::joined) }
        pool.view("claude", listOf(account("one", dir)))

        pool.accounts("claude", listOf(account("one", dir), account("two", dir)))
        pool.accounts("claude", listOf(account("one", dir), account("two", dir), account("three", dir)))

        val note = "A new account is available. Continue where you left off."
        assertEquals(listOf("a" to note, "b" to note, "c" to note), sent)
        assertEquals(
            listOf(
                "[limit-resume] released a on claude",
                "[limit-resume] released b on claude",
                "[limit-resume] could not reach c after a new login: no messaging socket",
            ),
            logged,
        )
    }

    private fun ending(outcome: OutcomeTag, head: String) = SessionEnding(outcome.wire, "one", null, ENDED, head)

    private companion object {
        const val ENDED = 1_000L
        const val NOW = 2_000L
    }
}
