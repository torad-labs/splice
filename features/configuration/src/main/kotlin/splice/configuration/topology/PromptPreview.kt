// NEW: V4-348 previews a draft head's file-backed instructions without writing its topology.
package splice.configuration.topology

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.prompt.HeadSystemPrompt
import splice.core.prompt.SystemPromptFileRead
import splice.core.prompt.SystemPromptMode
import splice.core.topology.Topology
import splice.core.topology.TopologyWriter
import splice.core.topology.TopologyWriterSource
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.http.JsonReply
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// The read cap bounds both memory and the time spent decoding an operator-selected file.
private const val PREVIEW_BYTES = 256 * 1024

// The first screen shows enough context without turning the editor into a full-file viewer.
private const val PREVIEW_LINES = 12

// Keep the response small even when a file has one very long line.
private const val PREVIEW_CHARS = 2_048

private data class PreviewAsk(val head: String, val file: String, val mode: SystemPromptMode)
private sealed class PreviewResult {
    data class Text(val full: String) : PreviewResult()
    data class Refused(val reason: String, val status: HttpStatusCode = HttpStatusCode.BadRequest) : PreviewResult()
}

/** One guarded route's bounded read, using the same path resolution and strip validation as a turn. */
internal class PromptPreview(private val source: TopologyWriterSource) {
    private val json = Json

    fun reply(body: String): JsonReply {
        val writer = source() ?: return response(
            PreviewResult.Refused(TOPOLOGY_UNWIRED, HttpStatusCode.ServiceUnavailable),
        )
        val outcome = when (val selected = parse(body)) {
            is Selection.Ask -> read(writer, selected.value)
            is Selection.Refused -> PreviewResult.Refused(selected.reason)
        }
        return response(outcome)
    }

    private sealed class Selection {
        data class Ask(val value: PreviewAsk) : Selection()
        data class Refused(val reason: String) : Selection()
    }

    private fun parse(body: String): Selection = Cancellables.runCatchingCancellable {
        json.parseToJsonElement(body) as? JsonObject
    }.fold(
        onSuccess = { tree ->
            val head = JsonScalars.str(tree, "head")?.takeIf(String::isNotBlank)
            val file = JsonScalars.str(tree, "file")?.takeIf(String::isNotBlank)
            val mode = SystemPromptMode.entries.firstOrNull { it.wire == JsonScalars.str(tree, "mode") }
            when {
                head == null -> Selection.Refused("plan is required")
                file == null -> Selection.Refused("instruction file is required")
                mode == null -> Selection.Refused("mode must be append, replace or strip")
                else -> Selection.Ask(PreviewAsk(head, file, mode))
            }
        },
        onFailure = { Selection.Refused("head, file and mode are required") },
    )

    private fun read(writer: TopologyWriter, ask: PreviewAsk): PreviewResult {
        val topology = Cancellables.runCatchingCancellable {
            json.decodeFromJsonElement(Topology.serializer(), writer.current())
        }.getOrElse { return PreviewResult.Refused("splice.toml cannot be read") }
        val dir = writer.path.parent ?: return PreviewResult.Refused("splice.toml has no parent directory")
        return if (ask.head !in topology.heads) {
            PreviewResult.Refused("plan is not in splice.toml", HttpStatusCode.NotFound)
        } else {
            resolved(dir, ask)
        }
    }

    private fun resolved(dir: Path, ask: PreviewAsk): PreviewResult {
        var observed: PreviewResult? = null
        val resolved = Cancellables.runCatchingCancellable {
            HeadSystemPrompt(
                file = ask.file,
                mode = ask.mode,
                configDir = dir,
                source = "head:${ask.head}",
                readFile = SystemPromptFileRead { path ->
                    val result = file(path)
                    observed = result
                    (result as? PreviewResult.Text)?.full.orEmpty()
                },
            ).resolve()
        }
        return when {
            observed is PreviewResult.Refused -> checkNotNull(observed)
            resolved.isFailure -> PreviewResult.Refused("instruction file or matching patterns cannot be read")
            else -> observed ?: PreviewResult.Refused("instruction file was not read")
        }
    }

    /** NOFOLLOW both at the check and open: a planted link cannot redirect this read. */
    private fun file(path: Path): PreviewResult = when {
        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ->
            PreviewResult.Refused("instruction file must be a regular file")
        else -> Cancellables.runCatchingCancellable {
            val bytes = ByteBuffer.allocate(PREVIEW_BYTES + 1)
            Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                while (bytes.hasRemaining() && channel.read(bytes) > 0) Unit
            }
            bytes.flip()
            if (bytes.limit() > PREVIEW_BYTES) {
                PreviewResult.Refused("instruction file exceeds the preview byte limit")
            } else {
                decode(bytes)
            }
        }.fold(
            onSuccess = { it },
            onFailure = { PreviewResult.Refused("instruction file cannot be read") },
        )
    }

    private fun decode(bytes: ByteBuffer): PreviewResult = Cancellables.runCatchingCancellable {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(bytes).toString()
    }.fold(
        onSuccess = { text ->
            if ('\u0000' in text) {
                PreviewResult.Refused("instruction file is not text")
            } else {
                PreviewResult.Text(text)
            }
        },
        onFailure = { PreviewResult.Refused("instruction file is not UTF-8 text") },
    )

    private fun response(result: PreviewResult): JsonReply = when (result) {
        is PreviewResult.Refused -> JsonReply(
            result.status,
            buildJsonObject { put("error", result.reason) }.toString(),
        )
        is PreviewResult.Text -> {
            val shown = result.full.lineSequence().take(PREVIEW_LINES).joinToString("\n").take(PREVIEW_CHARS)
            val body = buildJsonObject {
                put("text", shown)
                put("chars", result.full.length)
                put("truncated", shown.length < result.full.length)
            }
            JsonReply(HttpStatusCode.OK, body.toString())
        }
    }
}
