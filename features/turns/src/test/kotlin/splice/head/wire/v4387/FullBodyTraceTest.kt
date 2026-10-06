package splice.head.wire.v4387

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.perf.PerfSnapshot
import splice.core.storage.ActivityDays
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.head.trace.body.TraceBodies
import splice.head.wire.ClientInbound
import java.nio.file.Files
import java.nio.file.Path

private const val BODY_CHARS = 5 shl 20
private const val TRACE_DAY = 1_789_725_600_000L

class FullBodyTraceTest {
    @Test
    fun `a five Mi character client body is retained whole by the booted default cap`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val config = ConfigService(paths).getConfig("muse")
        assertTrue(config.trace, "no override should build a trace writer")
        val body = "x".repeat(BODY_CHARS)
        val trace = splice.head.syntheticTraceStore(
            ActivityDays(paths.traceDir, "muse", 7, WallClock { TRACE_DAY }, ownerOnly = true),
            "muse",
            config.traceMaxBodyChars,
            WallClock { TRACE_DAY },
        )
        val meta = TurnMeta(
            compact = false,
            showReasoning = ReasoningDisplay.TEXT,
            stream = true,
            originalModel = "claude-muse--m",
            upstreamModel = "m",
            clientMaxTokens = 16,
            effort = "high",
            summary = "detailed",
            budgetTokens = null,
        )
        trace.begin(meta, ClientInbound("POST", "/v1/messages", emptyMap(), body))
            .finish("ok", PerfSnapshot(emptyMap(), emptyMap()))
        assertTrue(AsyncFileIo.drain(), "the trace write must finish before checking it")
        val file = paths.traceDir.resolve("muse-2026-09-18.jsonl")
        val record = Json.parseToJsonElement(Files.readString(file).trim()).jsonObject
        val client = TraceBodies().hydrate(record, file).getValue("client").jsonObject
        assertTrue(client.getValue("body").jsonPrimitive.content == body, "the five Mi body changed or was truncated")
        assertFalse(client.getValue("truncated").jsonPrimitive.content.toBoolean())
    }
}
