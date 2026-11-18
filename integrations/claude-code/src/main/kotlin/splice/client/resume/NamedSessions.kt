// NEW: Nov 16, 2026: the session a `-r` word names. Claude Code resolves a name itself, among the transcripts of the
// launch's folder, whoever wrote them: every head shares one transcript tree (V4-168). A launch reads the same name
// here first, so it knows which session the client is about to open before the client starts (LaunchResume).
package splice.client.resume

import splice.client.Keys
import splice.client.transcript.TranscriptMetadata
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.DirectoryListing
import splice.core.util.FilesListing
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** One session found by its name: its id and its transcript. */
public data class NamedSession(val id: String, val transcript: Path)

public class NamedSessions(
    private val listing: DirectoryListing = FilesListing,
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    // why: Claude Code names a folder's transcripts by the folder's path with every character outside letters and
    // digits a dash, the same name ResumeAcrossHeads prefers a copy under
    private val notAlphanumeric = Regex("[^A-Za-z0-9]")

    /** Every session named [name] among the transcripts [configDir]'s tree holds for the folder [cwd]: none when no
     *  session there carries the name, and more than one when several do, a session and the copy another command
     *  goes on with among them. A name is the last title the session was given, compared without regard to case. */
    public fun all(configDir: Path, cwd: String, name: String): List<NamedSession> {
        val wanted = TranscriptMetadata.title(name) ?: return emptyList()
        val folder = configDir.resolve(Keys.PROJECTS).resolve(cwd.replace(notAlphanumeric, "-"))
        return transcripts(folder).filter { titleOf(it).equals(wanted, ignoreCase = true) }
            .map { NamedSession(it.fileName.toString().removeSuffix(TRANSCRIPT_SUFFIX), it) }
    }

    /** A folder that is not there holds no named session, and neither does one that cannot be listed, which is
     *  said once in the log: the name stays the client's to resolve. */
    private fun transcripts(folder: Path): List<Path> =
        Cancellables.runCatchingCancellable { listing.list(folder) }
            .onFailure { cause ->
                if (cause !is NoSuchFileException) {
                    log(
                        "[resume] could not list $folder (${SafeFailureText.render(cause)}), so a -r name was not " +
                            "read there and is the client's to resolve\n",
                    )
                }
            }
            .getOrDefault(emptyList())
            .filter { it.fileName.toString().endsWith(TRANSCRIPT_SUFFIX) && Files.isRegularFile(it, NOFOLLOW_LINKS) }

    /** A transcript whose name cannot be read carries none here. */
    private fun titleOf(transcript: Path): String? =
        Cancellables.runCatchingCancellable { TranscriptMetadata.read(transcript)?.first }
            .onFailure { cause ->
                log(
                    "[resume] the name of ${transcript.fileName} could not be read " +
                        "(${SafeFailureText.render(cause)}); it is not counted for a -r name\n",
                )
            }
            .getOrNull()
}
