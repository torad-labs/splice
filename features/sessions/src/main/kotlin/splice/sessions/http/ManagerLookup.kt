// NEW: Oct 15, 2026 (the splice manager) — where the one manager session is right now, so a start never opens a second.
//
// In this order: the session the pointer names, or any session running with the manager's folder as its working
// directory (one the person started there himself is the manager too); then a pane splice opened for it that is still
// asking something before Claude Code registers (its folder trust question); then a transcript to resume, the
// pointer's own first, else the newest one Claude Code wrote for that folder in any head's tree. A session silent for
// half an hour (STALE) is still the manager while its terminal is there: it is resumable only once that terminal is
// gone, so a second client never runs on top of it.
package splice.sessions.http

import kotlinx.serialization.json.Json
import splice.core.util.FilesListing
import splice.core.util.JsonScalars
import splice.core.util.PathProbe
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import java.nio.file.Files
import java.nio.file.Path

private const val PROJECTS_DIR = "projects"
private const val TRANSCRIPT_SUFFIX = ".jsonl"
private const val LOOKUP_NOT_OURS = "splice did not start the manager, so its terminal cannot be read from here"
private const val LOOKUP_CLOSED = "the manager's terminal is closed"

/** Where the manager is. */
internal sealed class ManagerFound {
    /** Running under [session]. [record] is its registration, null while the pane splice opened for it asks something
     *  before Claude Code registers. */
    class Live(val session: String, val record: SessionRecord?) : ManagerFound()

    /** Nothing runs, and [session]'s transcript is there to resume. */
    class Resumable(val session: String) : ManagerFound()

    /** No manager yet. */
    data object Off : ManagerFound()
}

internal class ManagerLookup(private val ports: ManagerDrive) {
    /** Claude Code's session ids: a transcript file named otherwise (a sidechain's) is not a session to resume. */
    private val idShape = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val nonAlphanumeric = Regex("[^A-Za-z0-9]")

    fun find(folder: Path, pointer: ManagerPointer?): ManagerFound =
        running(folder, pointer) ?: pending(pointer) ?: resumable(folder, pointer) ?: ManagerFound.Off

    /** Whether [record] runs with [folder] as its working directory. */
    fun inFolder(record: SessionRecord, folder: Path): Boolean {
        val cwd = record.process.cwd ?: return false
        return Path.of(cwd).toAbsolutePath().normalize() == folder.toAbsolutePath().normalize()
    }

    private fun running(folder: Path, pointer: ManagerPointer?): ManagerFound? {
        // a LIVE registration first: a resumed client registers beside the silent one it took over from
        val records = ports.registry?.read().orEmpty().filter { it.availability != SessionAvailability.GONE }
            .sortedBy { it.availability != SessionAvailability.LIVE }
        val record = records.firstOrNull { it.sessionId != null && it.sessionId == pointer?.sessionId }
            ?: records.firstOrNull { it.sessionId != null && inFolder(it, folder) }
        val session = record?.sessionId ?: return null
        val gone = record.availability == SessionAvailability.STALE && terminalGone(session)
        return if (gone) ManagerFound.Resumable(session) else ManagerFound.Live(session, record)
    }

    /** True only when the drive says the session's terminal is closed or gone. Not ours, taken by something else, or
     *  no terminal wired: its client may still run, so it is not resumed over. */
    private fun terminalGone(session: String): Boolean {
        val driving = ports.drive.driver() ?: return false
        val refusal = ports.drive.locate(driving, session, LOOKUP_NOT_OURS, LOOKUP_CLOSED).refusal ?: return false
        val reason = JsonScalars.objectOrNull(Json, refusal.body)?.let { JsonScalars.str(it, "reason") }
        return reason == Refusal.CLOSED.key || reason == Refusal.PANE_GONE.key
    }

    /** The pointer's session when Claude Code has not registered it yet and the pane splice opened for it is open:
     *  a start that stopped on a question, which the person answers on the screen. */
    private fun pending(pointer: ManagerPointer?): ManagerFound? {
        val session = pointer?.sessionId ?: return null
        val registered = ports.registry?.read().orEmpty()
            .any { it.sessionId == session && it.availability != SessionAvailability.GONE }
        val pane = ports.drive.driver()?.takeUnless { registered }?.let { driving ->
            driving.panes.paneFor(session)?.takeIf(driving.terminal::isOpen)
        }
        return pane?.let { ManagerFound.Live(session, null) }
    }

    private fun resumable(folder: Path, pointer: ManagerPointer?): ManagerFound? {
        val dirs = projectDirs(folder)
        val own = pointer?.sessionId
            ?.takeIf { id -> dirs.any { Files.isRegularFile(it.resolve(id + TRANSCRIPT_SUFFIX)) } }
        return (own ?: newest(dirs))?.let { ManagerFound.Resumable(it) }
    }

    /** The folder's project directory in every transcript tree, the vanilla one and each head's (TranscriptRoots):
     *  Claude Code names it after the working directory, each character not a letter or digit as '-'. */
    private fun projectDirs(folder: Path): List<Path> {
        val name = folder.toAbsolutePath().normalize().toString().replace(nonAlphanumeric, "-")
        return ports.roots.treesFor(null).map { it.resolve(PROJECTS_DIR).resolve(name) }
            .filter { Files.isDirectory(it) }
    }

    /** The id of the newest session transcript among [dirs]. */
    private fun newest(dirs: List<Path>): String? = dirs.flatMap(FilesListing::list)
        .filter { transcriptId(it) != null && Files.isRegularFile(it) }
        .maxByOrNull { PathProbe.modified(it)?.toMillis() ?: 0L }
        ?.let(::transcriptId)

    /** The session id a transcript file is named by, or null for any other file. */
    private fun transcriptId(file: Path): String? {
        val name = file.fileName.toString()
        return name.removeSuffix(TRANSCRIPT_SUFFIX).takeIf { name.endsWith(TRANSCRIPT_SUFFIX) && idShape.matches(it) }
    }
}
