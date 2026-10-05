package splice.head.wire.v4368

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.perf.PerfSnapshot
import splice.core.storage.ActivityDays
import splice.core.storage.DayFileRemoval
import splice.core.storage.DayFiles
import splice.core.terminal.TerminalOutput
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.AsyncFileIo
import splice.core.util.EnvReader
import splice.core.util.WallClock
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.trace.TraceCommand
import splice.head.trace.TraceDirPort
import splice.head.trace.TraceHeadSource
import splice.head.trace.TraceHeads
import splice.head.trace.TraceQuery
import splice.head.trace.TraceRoute
import splice.head.wire.ClientInbound
import splice.head.wire.TraceDeleteRoutes
import splice.head.wire.TraceStore
import splice.head.wire.TurnIdMint
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

private const val DAY_ONE = 1_789_725_600_000L // 2026-09-18T10:00Z

class TraceDeleteRoutesTest {
    private val noCompaction = object : HeadCompactSource {
        override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
    }
    private val heads = TurnsHeadLookup { name ->
        if (name in setOf("alpha", "beta")) listOf(TurnsHead(name, noCompaction)) else emptyList()
    }

    private fun writer(dir: Path, head: String): TraceStore {
        var id = 0
        return TraceStore(
            ActivityDays(dir, head, 7, WallClock { DAY_ONE }, ownerOnly = true),
            head,
            maxBodyChars = 4096,
            now = WallClock { DAY_ONE },
            ids = TurnIdMint { "turn-$head-${++id}" },
        )
    }

    private fun record(store: TraceStore, head: String) {
        val meta = TurnMeta(
            compact = false,
            showReasoning = ReasoningDisplay.TEXT,
            stream = true,
            originalModel = "claude-$head--m",
            upstreamModel = "m",
            clientMaxTokens = 64,
            effort = "medium",
            summary = null,
            budgetTokens = null,
            sessionId = "synthetic-session",
        )
        store.begin(meta, ClientInbound("POST", "/v1/messages", emptyMap(), "synthetic request"))
            .finish("ok", PerfSnapshot(mapOf("total" to 1L), mapOf("in_tokens" to 1L)))
        assertTrue(AsyncFileIo.drain(), "the trace write reached its day file")
    }

    private fun route(root: Path, dir: Path?, removal: DayFileRemoval? = null) = TraceDeleteRoutes(
        heads,
        TraceDirPort { dir },
        ConfigService(StatePaths(baseOverride = root.resolve("state")), envReader = EnvReader { null }),
        removal,
    )

    private fun field(body: String, key: String): String =
        Json.parseToJsonElement(body).jsonObject.getValue(key).jsonPrimitive.content

    private fun assertDeletedCli(dir: Path) {
        val out = StringBuilder()
        val command = TraceCommand(
            TerminalOutput { out.appendLine(it) },
            TerminalOutput { error("unexpected trace error: $it") },
            TraceHeadSource { TraceHeads.Configured("splice.toml", setOf("alpha", "beta")) },
            { dir },
        )
        assertTrue(command.trace(listOf("alpha"), EnvReader { null }))
        assertTrue(out.toString().contains("trace deleted"), out.toString())
        out.clear()
        assertTrue(command.trace(listOf("alpha", "--json"), EnvReader { null }))
        assertEquals("deleted", field(out.toString(), "state"))
        assertEquals("trace deleted", field(out.toString(), "reason"))
    }

    @Test
    fun `only one heads trace is deleted and every reader calls it deleted`(@TempDir root: Path) = runBlocking {
        val dir = root.resolve("trace")
        val alpha = writer(dir, "alpha")
        record(alpha, "alpha")
        record(writer(dir, "beta"), "beta")
        val own = dir.resolve("alpha-2026-09-18.jsonl")
        val ownPack = dir.resolve("alpha-2026-09-18.jsonl.bodies2")
        val other = dir.resolve("beta-2026-09-18.jsonl")
        val otherPack = dir.resolve("beta-2026-09-18.jsonl.bodies2")
        val planted = Files.writeString(dir.resolve("leave-me.txt"), "unrelated")
        val outside = Files.writeString(root.resolve("private-target"), "external bytes")
        Files.createSymbolicLink(dir.resolve("alpha-2026-09-19.jsonl"), outside)
        val routes = route(root, dir)
        val reader = TraceRoute(heads, TraceDirPort { dir }, Dispatchers.Unconfined)
        val before = routes.kept("alpha")
        assertEquals(HttpStatusCode.OK, before.status)
        assertEquals("kept", field(before.body, "state"))
        assertEquals("1", field(before.body, "days"))
        assertEquals("1", field(before.body, "records"))
        assertEquals((Files.size(own) + Files.size(ownPack)).toString(), field(before.body, "bytes"))
        assertEquals("2026-09-18", field(before.body, "oldest"))
        assertEquals("2026-09-25", field(before.body, "ages_out"))
        assertEquals("1", field(reader.read("alpha", TraceQuery(null, null, null)).body, "on_disk"))

        val deleted = routes.delete("alpha")
        assertEquals(HttpStatusCode.OK, deleted.status, deleted.body)
        assertFalse(Files.exists(own), "the requested head loses its index")
        assertFalse(Files.exists(ownPack), "the requested head loses its body pack")
        assertTrue(Files.exists(other), "another head keeps its trace")
        assertTrue(Files.exists(otherPack), "another head keeps its body pack")
        assertEquals("unrelated", Files.readString(planted))
        assertEquals("external bytes", Files.readString(outside))
        assertFalse(Files.exists(dir.resolve("alpha-2026-09-19.jsonl"), LinkOption.NOFOLLOW_LINKS))
        assertEquals("deleted", field(deleted.body, "state"))
        assertEquals("1", field(deleted.body, "records"))
        assertEquals("0", field(routes.kept("alpha").body, "records"))
        assertEquals("deleted", field(reader.read("alpha", TraceQuery(null, null, null)).body, "state"))
        assertEquals("0", field(reader.read("alpha", TraceQuery(null, null, null)).body, "on_disk"))
        val missing = reader.read("alpha", TraceQuery(null, null, "turn-alpha-1"))
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals("trace deleted", field(missing.body, "error"))

        assertDeletedCli(dir)

        record(alpha, "alpha")
        assertEquals("kept", field(routes.kept("alpha").body, "state"))
        assertEquals("1", field(routes.kept("alpha").body, "records"))
        assertEquals("1", field(reader.read("alpha", TraceQuery(null, null, null)).body, "on_disk"))
    }

    @Test
    fun `an IOException from the delete operation reports a conflict and preserves all retained bytes`(
        @TempDir root: Path,
    ) {
        val dir = Files.createDirectory(root.resolve("trace"))
        val day = Files.writeString(dir.resolve("alpha-2026-09-18.jsonl"), "{}\n")
        val pack = Files.writeString(dir.resolve("alpha-2026-09-18.jsonl.bodies"), "synthetic pack")
        val attempted = mutableListOf<Path>()
        val removal = DayFileRemoval { file ->
            val _ = attempted.add(file)
            throw FileSystemException(file.toString(), null, "Read-only file system")
        }
        val routes = route(root, dir, removal)
        val result = routes.delete("alpha")
        assertEquals(HttpStatusCode.Conflict, result.status, result.body)
        assertTrue(result.body.contains("cannot read or delete alpha's trace"), result.body)
        assertTrue(result.body.contains(dir.toString()), result.body)
        assertTrue(day in attempted && pack in attempted, "the real purge attempted both retained files: $attempted")
        assertEquals("{}\n", Files.readString(day))
        assertEquals("synthetic pack", Files.readString(pack))
        assertFalse(DayFiles(dir, "alpha").deleted(), "a refused purge is never marked deleted")
        assertEquals("kept", field(routes.kept("alpha").body, "state"))
    }

    @Test
    fun `unknown and unwired heads cannot delete any trace`(@TempDir root: Path) {
        val dir = Files.createDirectory(root.resolve("trace"))
        val unknown = route(root, dir).delete("typo")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, route(root, null).delete("alpha").status)
        assertTrue(Files.list(dir).use { it.toList() }.isEmpty())
        assertEquals("empty", field(route(root, dir).kept("alpha").body, "state"))
        val deleted = route(root, dir).delete("alpha")
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertEquals("deleted", field(deleted.body, "state"))
        assertEquals("0", field(deleted.body, "records"))
    }
}
