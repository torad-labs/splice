// NEW: Oct 15, 2026 (the splice manager) — what the manager's routes read from this daemon: its folder as ManagerHome
// keeps it, and the heads it can run on as GET /api/heads and the Teams start commands see them.
package splice.app.control.mount

import kotlinx.serialization.json.JsonObject
import splice.app.control.ManagedHead
import splice.app.control.api.HeadResolver
import splice.app.manager.ManagerHome
import splice.core.util.JsonScalars
import splice.core.wire.ControlFields.KEY
import splice.core.wire.ControlFields.LABEL
import splice.sessions.http.StartCommand
import splice.sessions.http.StartCommands
import splice.sessions.manager.ManagerFolder
import splice.sessions.manager.ManagerHeadChoice
import splice.sessions.manager.ManagerHeads
import java.nio.file.Path

/** The manager's folder, the parent of splice.toml, with the files ManagerHome ships into it. */
internal class HomeManagerFolder(private val home: ManagerHome) : ManagerFolder {
    override fun folder(): Path = home.folder()

    override fun ensure(): Path = home.ensure()
}

/** The heads the manager can run on: every launchable head in the order GET /api/heads lists them (HeadResolver), each
 *  ready or not by the same reading that route serves and the same command check a team start makes. */
internal class FleetHeadChoices(
    private val resolver: HeadResolver,
    private val heads: Map<String, ManagedHead>,
    private val commands: StartCommands,
) : ManagerHeads {
    override fun heads(): List<ManagerHeadChoice> = resolver.headStatuses().mapNotNull { status ->
        val key = JsonScalars.str(status, KEY)?.takeIf { heads[it]?.launchSpec != null } ?: return@mapNotNull null
        val why = why(key, status)
        ManagerHeadChoice(key, JsonScalars.str(status, LABEL) ?: key, ready = why == null, why = why)
    }

    /** Why [key] cannot carry the manager now, in his words, or null when it can: its command is not linked, its
     *  window is full or its provider said to wait, its local runtime did not answer, or it is stopped. */
    private fun why(key: String, status: JsonObject): String? = when {
        commands.forHead(key) is StartCommand.Refused -> "not installed as a command: run splice install $key"
        status["quotaFull"] != null || status["quotaResetAtEpochSeconds"] != null -> "out of quota"
        status["runtimeNotAnswering"] != null -> "down: its runtime is not answering"
        JsonScalars.str(status, "running") == "false" -> "down: it is stopped"
        else -> null
    }
}
