package splice.app.control

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.sessions.http.TranscriptRoots
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionStatus
import splice.sessions.transcript.SentTexts
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptPage
import splice.sessions.transcript.TranscriptRole
import java.nio.file.Path

class TranscriptWakeGuardTest {
    @TempDir
    lateinit var dir: Path

    private val record = SessionRecord(
        sessionId = "waiting",
        name = null,
        status = SessionStatus("idle"),
        route = SessionRoute.Head("test"),
        availability = SessionAvailability.LIVE,
        process = SessionProcess(pid = 1, cwd = null, startedAt = null, updatedAt = 1, messagingSocketPath = null),
        client = SessionClient(kind = null, version = "test"),
    )

    private fun guard(messages: List<TranscriptMessage>): TranscriptLimitEnding {
        val source = object : SessionTranscripts {
            override fun page(sessionId: String, roots: List<Path>, cursor: String?, limit: Int, cwd: String?) =
                TranscriptLookup.Found(TranscriptPage(sessionId, "test", messages, null, emptyMap()))

            override fun pageBefore(sessionId: String, roots: List<Path>, before: String?, limit: Int, cwd: String?) =
                page(sessionId, roots, before, limit, cwd)

            override fun sentTexts(sessionId: String, roots: List<Path>, ids: Set<String>) =
                SentTexts(null, emptyMap(), ids)
        }
        return TranscriptLimitEnding(
            ConfigService(StatePaths(baseOverride = dir), envReader = EnvReader { null }, log = LogSink {}),
            source,
            TranscriptRoots(vanilla = dir),
        )
    }

    @Test
    fun `an old API error does not turn activity before the current ending into a new touch`() {
        val history = listOf(
            TranscriptMessage(0, TranscriptRole.SYSTEM, 10, "API error: old limit"),
            TranscriptMessage(1, TranscriptRole.USER, 15, "continue"),
            TranscriptMessage(2, TranscriptRole.ASSISTANT, 25, "partial answer"),
        )
        assertTrue(guard(history).untouched(record, 30))
        val resumed = history + TranscriptMessage(3, TranscriptRole.USER, 35, "next request")
        assertFalse(guard(resumed).untouched(record, 30))
    }

    @Test
    fun `the current API error allows the earlier prompt copied after it but detects a later stop`() {
        val history = listOf(
            TranscriptMessage(0, TranscriptRole.SYSTEM, 40, "API error: limit"),
            TranscriptMessage(1, TranscriptRole.USER, 20, "the original prompt"),
        )
        assertTrue(guard(history).untouched(record, 30))
        val stopped = history + TranscriptMessage(2, TranscriptRole.USER, 45, "interrupted")
        assertFalse(guard(stopped).untouched(record, 30))
    }

    @Test
    fun `a message without a time after the limit's error prevents an automatic wake`() {
        val history = listOf(TranscriptMessage(0, TranscriptRole.SYSTEM, null, "API error: limit"))
        assertTrue(guard(history).untouched(record, 30))
        val stopped = history + TranscriptMessage(1, TranscriptRole.USER, null, "interrupted")
        assertFalse(guard(stopped).untouched(record, 30))
    }
}
