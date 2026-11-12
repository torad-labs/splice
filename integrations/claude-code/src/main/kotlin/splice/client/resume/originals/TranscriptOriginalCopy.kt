// NEW: stream and force an original's bytes before its immutable name is published.
package splice.client.resume.originals

import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE

/** Copies into an already owner-only staged file and forces its bytes before returning. */
public fun interface TranscriptOriginalCopy {
    public fun copy(source: Path, staged: Path)
}

internal object ForcedTranscriptOriginalCopy : TranscriptOriginalCopy {
    override fun copy(source: Path, staged: Path) {
        FileChannel.open(staged, WRITE, NOFOLLOW_LINKS).use { channel ->
            val output = Channels.newOutputStream(channel)
            Files.newInputStream(source).use { it.copyTo(output) }
            channel.force(true)
        }
    }
}
