// NEW: server mode (docs/specs/server-mode.md, F16). Append-only audit log of every administrative action on the
// server (who created, revoked or changed what, and when), kept at `<state>/server/audit.jsonl` via JsonlSink and
// readable through `/server/api/audit`, `/api/server/audit`, and `splice server audit`.
package splice.server.audit

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonlSink
import splice.server.ServerAnswer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

public class ServerAudit(
    private val file: Path,
    private val io: CoroutineDispatcher,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Records one admin [action] by [actor] against [target] with optional structured [details]. */
    public suspend fun record(
        actor: String,
        action: String,
        target: String,
        details: JsonObject = JsonObject(emptyMap()),
    ) {
        val row = buildJsonObject {
            put("ts", Instant.ofEpochMilli(clock.millis()).toString())
            put("actor", actor)
            put("action", action)
            put("target", target)
            if (details.isNotEmpty()) put("details", details)
        }
        withContext(io) {
            file.parent?.let(Files::createDirectories)
            JsonlSink.appendLine(file, row.toString())
        }
    }

    /** The newest [tail] audit entries (clamped to 1..MAX_AUDIT_TAIL), oldest-first within the slice. */
    public suspend fun list(tail: Int = DEFAULT_AUDIT_TAIL): ServerAnswer {
        val count = tail.coerceIn(1, MAX_AUDIT_TAIL)
        val lines = withContext(io) {
            if (!Files.exists(file)) {
                emptyList()
            } else {
                JsonlSink.readTailAt(file, maxBytes = AUDIT_TAIL_WINDOW_BYTES, limit = count).lines
            }
        }
        val entries = buildJsonArray {
            lines.forEach { raw ->
                runCatchingParse(raw)?.let(::add)
            }
        }
        return ServerAnswer.Done(HttpStatusCode.OK, buildJsonObject { put("entries", entries) })
    }

    private fun runCatchingParse(raw: String): JsonObject? = try {
        Json.parseToJsonElement(raw).jsonObject
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal const val CONTROL_ACTOR: String = "control"

// why: default tail returned by /api/server/audit and /server/api/audit when the caller names no ?tail.
public const val DEFAULT_AUDIT_TAIL: Int = 100

// why: upper bound on one audit tail read so a single HTTP response stays bounded.
private const val MAX_AUDIT_TAIL = 2_000

// why: trailing byte window read from audit.jsonl when listing up to MAX_AUDIT_TAIL entries (~2 KiB per entry max).
private const val AUDIT_TAIL_WINDOW_BYTES = 4 * 1024 * 1024
