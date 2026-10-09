package splice.client.transcript.v4427

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.transcript.TranscriptReader
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.sessions.activity.ActivityStores
import splice.sessions.activity.MessageEdge
import splice.sessions.http.ActivityRoutes
import splice.sessions.http.ActivitySource
import splice.sessions.http.SentTextSource
import splice.sessions.registry.SessionRegistry
import java.nio.file.Path

// why: a fixed synthetic day makes the edge store deterministic without a real session or process.
private const val AT = 1_790_000_000_000L
private const val EDGE_LINES = 20_000

class CachedEdgesTest {
    @Test
    fun `the second edges response reads zero transcript bytes and keeps its redacted hand-offs`(@TempDir home: Path) {
        val root = home.resolve("tree")
        val file = root.resolve("projects/project/$SESSION.jsonl")
        SyntheticTranscript.write(
            file,
            EDGE_LINES,
            mapOf(19_990 to SyntheticTranscript.handOff("toolu_send", "done token=abcdefgh12345678")),
        )
        val counter = CountingOpener()
        val reader = TranscriptReader(counter)
        val stores = ActivityStores(home.resolve("activity"), 90, "*", WallClock { AT })
        stores.edges.record(MessageEdge(SESSION, "peer", AT, "toolu_send"))
        AsyncFileIo.drain()
        val route = ActivityRoutes(
            SessionRegistry(home.resolve("registry"), routeOf = { splice.sessions.registry.SessionRoute.Unknown }),
            ActivitySource { stores },
            SentTextSource { session, _, ids -> reader.sentTexts(session, listOf(root), ids) },
        )
        val first = route.edges(SESSION)
        assertTrue(counter.drain() > 30L shl 20)
        val start = System.nanoTime()
        val second = route.edges(SESSION)
        assertTrue(System.nanoTime() - start < 1_000_000_000L)
        assertEquals(0L, counter.drain())
        assertEquals(first, second)
        val edge = Json.parseToJsonElement(second.body).jsonObject.getValue("edges").jsonArray.single().jsonObject
        assertEquals("done token=[redacted]", edge.getValue("text").jsonPrimitive.content)
    }
}
