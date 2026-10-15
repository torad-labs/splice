// NEW: Oct 15, 2026 (the splice manager) — what the manager's routes need from the daemon, as ports: its folder, the
// heads it can run on, and the session machinery Teams starts members with and Continue on moves sessions with.
package splice.sessions.http

import splice.sessions.registry.SessionSource
import java.nio.file.Path

/** The manager's folder: splice's global settings folder, with the files the manager reads in it (ManagerHome). */
public interface ManagerFolder {
    /** Where the folder is. Reads nothing and writes nothing: GET /api/manager only reads. */
    public fun folder(): Path

    /** The folder with its files in place, created or refreshed; throws when the folder cannot be created. */
    public fun ensure(): Path
}

/** The heads the person can start the manager on, in the order GET /api/heads lists them, each ready or not. */
public fun interface ManagerHeads {
    public fun heads(): List<ManagerHeadChoice>
}

/** The machinery the manager is found, started and moved with, the same instances Sessions and Teams use: the one
 *  drive every pane is reached through, each head's launch command, the registry's word that a session came up, the
 *  registry itself, the transcript trees a resume is looked for in, and Continue on. */
public data class ManagerDrive(
    val drive: SessionDrive,
    val commands: StartCommands,
    val arrival: SessionArrival,
    val registry: SessionSource?,
    val roots: TranscriptRoots,
    val continueOn: SessionContinue,
)
