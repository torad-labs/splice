// NEW: V4-348 checks a head's file-backed instructions before a topology write stores them.
package splice.configuration.topology

import splice.core.prompt.HeadSystemPrompt
import splice.core.prompt.SystemPromptFileRead
import splice.core.prompt.SystemPromptMode
import splice.core.topology.TopologyWriter
import splice.core.util.Cancellables
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// The read cap bounds both memory and the time spent decoding an operator-selected file.
private const val PREVIEW_BYTES = 256 * 1024

private data class PreviewAsk(val head: String, val file: String, val mode: SystemPromptMode)
private sealed class PreviewResult {
    data class Text(val full: String) : PreviewResult()
    data class Refused(val reason: String) : PreviewResult()
}

/** A bounded read of a head's instruction file, using the same path resolution and strip validation as a turn. */
internal class PromptPreview {
    /** V4-400: why this file cannot be read, or null when it reads: Save never stores a file a turn would refuse. */
    fun refusal(writer: TopologyWriter, head: String, file: String, mode: SystemPromptMode): String? {
        val dir = writer.path.parent ?: return "splice.toml has no parent directory"
        return (resolved(dir, PreviewAsk(head, file, mode)) as? PreviewResult.Refused)?.reason
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
        Files.notExists(path, LinkOption.NOFOLLOW_LINKS) -> PreviewResult.Refused("instruction file does not exist")
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
}
