// NEW: Oct 12, 2026 (splice-lead) — the primary transcripts under each root's projects folder, split out of
// TranscriptHistoryIndex.kt: what is in each folder, which files are trusted, and what each one holds.
package splice.client.transcript

import splice.client.resume.ResumableTranscript
import splice.core.util.SafeFailureText
import splice.sessions.transcript.SessionHistoryRoot
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path

private val SESSION_FILE = Regex(
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.jsonl",
)

/** What one scan has found so far: the sessions, why files were skipped, and what could not be read. */
internal class HistoryScanState {
    val sessions = linkedMapOf<String, HistoryFacts>()
    val skipped = mutableMapOf<String, Int>()
    val errors = mutableListOf<String>()

    fun skip(reason: String) {
        skipped[reason] = (skipped[reason] ?: 0) + 1
    }
}

internal class TranscriptTreeReader(
    private val state: HistoryScanState,
    private val transcriptFacts: TranscriptFactsCache.Pass,
) {
    private val seenProjects = mutableSetOf<Path>()

    fun read(root: SessionHistoryRoot) = transcripts(root)

    private fun transcripts(root: SessionHistoryRoot) {
        val projects = root.dir.resolve("projects")
        try {
            val canonical = projects.toRealPath()
            if (!seenProjects.add(canonical)) return
            Files.newDirectoryStream(projects).use { folders ->
                folders.forEach { inspectFolder(it, canonical, root.head) }
            }
        } catch (_: NoSuchFileException) {
            // An uncreated projects tree is no evidence of a lost history file.
        } catch (error: IOException) {
            state.errors += "$projects: ${SafeFailureText.render(error)}"
        }
    }

    private fun inspectFolder(folder: Path, projects: Path, head: String?) {
        try {
            if (!insideProjects(folder, projects)) {
                state.skip("project path outside tree")
                return
            }
            Files.newDirectoryStream(folder).use { entries ->
                entries.forEach { inspectFile(it, projects, head) }
            }
        } catch (_: NotDirectoryException) {
            state.skip("non-project artifact")
        } catch (error: IOException) {
            state.errors += "$folder: ${SafeFailureText.render(error)}"
        }
    }

    /** A real directory directly under projects is inside it without resolving; a link is resolved. */
    private fun insideProjects(folder: Path, projects: Path): Boolean =
        EntryStat(folder, followLinks = false).isDirectory ||
            folder.toRealPath().startsWith(projects)

    /** One stat of the entry tells what it is. A plain file in a folder that is inside projects is inside it, so its
     *  facts are held by its stamp; a link is followed and read each time, as it always was. */
    private fun inspectFile(file: Path, projects: Path, head: String?) {
        val name = file.fileName.toString()
        try {
            val attrs = EntryStat(file, followLinks = false)
            when {
                attrs.isSymbolicLink -> inspectLink(file, projects, head)
                attrs.isDirectory -> state.skip("nested artifacts")
                !name.endsWith(".jsonl") -> state.skip("non-jsonl artifact")
                !SESSION_FILE.matches(name) -> state.skip("non-session jsonl")
                attrs.isRegularFile -> heldTranscript(file, attrs, head)
                else -> state.skip("untrusted transcript path")
            }
        } catch (error: IOException) {
            state.errors += "$file: ${SafeFailureText.render(error)}"
        }
    }

    private fun inspectLink(file: Path, projects: Path, head: String?) {
        val name = file.fileName.toString()
        when {
            Files.isDirectory(file) -> state.skip("nested artifacts")
            !name.endsWith(".jsonl") -> state.skip("non-jsonl artifact")
            !SESSION_FILE.matches(name) -> state.skip("non-session jsonl")
            else -> readTranscript(file, projects, head)
        }
    }

    private fun heldTranscript(file: Path, attrs: EntryStat, head: String?) {
        val stamp = TranscriptStamp(attrs.size, attrs.modifiedMs, attrs.identity)
        val known = transcriptFacts.lookup(file, stamp)
            ?: factsOf(file).also { transcriptFacts.remember(file, stamp, it) }
        record(file, attrs.modifiedMs, known, head)
    }

    private fun factsOf(file: Path): TranscriptFacts {
        val metadata = TranscriptMetadata.read(file)
        return TranscriptFacts(metadata?.first, metadata?.second)
    }

    private fun record(file: Path, modified: Long, known: TranscriptFacts, head: String?) {
        val id = file.fileName.toString().removeSuffix(".jsonl")
        val facts = state.sessions.getOrPut(id) { HistoryFacts(id) }
        if (!facts.hasTranscript || modified >= (facts.transcriptAt ?: 0L)) {
            facts.transcriptAt = modified
            facts.transcriptName = known.name
            facts.project = facts.project ?: known.cwd
            if (head != null) facts.head = head
        }
        facts.hasTranscript = true
    }

    private fun trustedPrimary(file: Path, projects: Path): Boolean =
        Files.isRegularFile(file) && file.toRealPath().startsWith(projects)

    private fun readTranscript(file: Path, projects: Path, head: String?) {
        try {
            if (!trustedPrimary(file, projects)) {
                state.skip("untrusted transcript path")
                return
            }
            record(
                file,
                Files.getLastModifiedTime(file).toMillis(),
                factsOf(file, ResumableTranscript.accepts(file)),
                head,
            )
        } catch (error: IOException) {
            state.errors += "$file: ${SafeFailureText.render(error)}"
        }
    }
}
