// NEW: Oct 15, 2026 (the splice manager) — opening the manager's terminal and waiting for Claude Code to register in
// it, the way a team member's start does (TeamStart): a second at a time up to ARRIVAL_SECONDS, reading the screen
// between, so a question asked before the session registers (the folder trust question a new folder gets) is the
// person's to answer and the pane stays open for it. A resume can come up under a new id (a cross-head resume copies
// the session, ResumeAcrossHeads), so a session that registers in the manager's folder counts as the one started.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.session.SessionPane
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.http.JsonReply
import splice.sessions.prompt.ScreenChoices
import splice.sessions.registry.SessionAvailability
import java.nio.file.Path

/** How a start ended. */
internal sealed class ManagerUp {
    /** Claude Code registered, under [session]. */
    class Up(val session: String) : ManagerUp()

    /** The pane is open and asks something before Claude Code registers; [session] is the id it was opened under. */
    class Asked(val session: String) : ManagerUp()

    /** Nothing came up; [reply] says why, and the pane is closed. */
    class Missed(val reply: JsonReply) : ManagerUp()
}

internal class ManagerLaunch(private val ports: ManagerDrive, private val lookup: ManagerLookup) {
    /** How a screen is read for a question asked before the session registers: the drive's own reading. */
    private val choices = ScreenChoices()

    /** Opens [argv] in [folder] with its pane remembered under [session], and waits for it. */
    suspend fun open(driving: SessionDriver, session: String, argv: List<String>, folder: Path): ManagerUp =
        Cancellables.runCatchingCleanup { driving.terminal.open(session, argv, folder.toString()) }.fold(
            onSuccess = { pane ->
                driving.panes.remember(session, pane)
                await(driving, session, pane, folder)
            },
            onFailure = {
                ManagerUp.Missed(
                    Refusals.reply(
                        HttpStatusCode.Conflict,
                        "the manager's terminal would not open: " + SafeFailureText.render(it),
                        Refusal.LAUNCH_FAILED,
                    ),
                )
            },
        )

    /** Waits up to [ARRIVAL_SECONDS] on [pane], opened under [session]. */
    suspend fun await(driving: SessionDriver, session: String, pane: SessionPane, folder: Path): ManagerUp {
        repeat(ARRIVAL_SECONDS.toInt()) {
            probe(driving, session, pane, folder)?.let { return it }
        }
        return ManagerUp.Missed(didNotComeUp(driving, session, pane))
    }

    /** One second of the wait: up under its own id or a copy's, asking something, or null to keep waiting. */
    private suspend fun probe(driving: SessionDriver, session: String, pane: SessionPane, folder: Path): ManagerUp? {
        if (ports.arrival.arrived(session, 1)) return ManagerUp.Up(session)
        val copy = ports.registry?.read().orEmpty()
            .firstOrNull { it.availability == SessionAvailability.LIVE && lookup.inFolder(it, folder) }?.sessionId
        if (copy != null) {
            driving.panes.remember(copy, pane)
            return ManagerUp.Up(copy)
        }
        val asked = Cancellables.runCatchingCleanup { choices.on(driving.terminal.screen(pane)).offering }
        return if (asked.getOrDefault(false)) ManagerUp.Asked(session) else null
    }

    /** The bottom of the screen, blank lines dropped, because the reason is usually on it; then the pane is closed. */
    private fun didNotComeUp(driving: SessionDriver, session: String, pane: SessionPane): JsonReply {
        val screen = Cancellables.runCatchingCleanup { driving.terminal.screen(pane) }
            .fold(
                { it.lines().filter(String::isNotBlank).takeLast(SCREEN_TAIL_LINES).joinToString("\n") },
                { "(the screen could not be read: ${it.message})" },
            )
        val closing = Cancellables.runCatchingCleanup { driving.terminal.close(pane) }
            .fold({ "" }, { "; its terminal would not close: ${it.message}" })
        driving.panes.forget(session)
        val said = "the manager did not start: Claude Code had not registered after ${ARRIVAL_SECONDS}s"
        return JsonReply(
            HttpStatusCode.Conflict,
            buildJsonObject {
                put("error", said + closing)
                put("reason", Refusal.LAUNCH_FAILED.key)
                put("screen", screen)
            }.toString(),
        )
    }
}
