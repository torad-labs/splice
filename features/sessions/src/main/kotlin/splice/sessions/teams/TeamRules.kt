// NEW: V4-160 — the pure rules a TeamStore write applies, moved out of TeamStore (function ceiling,
// 2026-09-18): what makes a team valid, what a re-saved slot keeps, and how a slot remembers every
// session it ever held. No file, no lock, no clock of its own: TeamStore calls these under its lock.
package splice.sessions.teams

import java.util.UUID

// why: 12 hex characters of a random UUID, which is 48 bits — enough that a collision across the
// teams one daemon holds is not a case worth handling, and short enough to read in a URL.
private const val ID_HEX_CHARS = 12

/** What probing a team's declared repo path found. [TeamStore] carries the production default that
 *  reads the real filesystem; this pure port is what makes [TeamRules.validate] testable without one. */
public enum class RepoPathCheck { DIRECTORY, MISSING, NOT_A_DIRECTORY }

/** Answers what [path] is on disk, so a team's repo is validated without this class owning a
 *  filesystem of its own. */
public fun interface RepoProbe {
    public operator fun invoke(path: String): RepoPathCheck
}

internal class TeamRules {

    /** [slot] as re-saved over [before]: a binding the upsert left out is kept, the history is kept,
     *  and the instructions stamp moves only when the instructions did. */
    fun carried(slot: TeamSlot, before: TeamSlot?, now: Long): TeamSlot {
        val instructionsMoved = before != null && before.instructions != slot.instructions
        return remembered(
            slot.copy(
                session = slot.session ?: before?.session,
                instructionsUpdatedAt = if (instructionsMoved) now else stampOf(before, slot),
                sessionsHistory = before?.sessionsHistory ?: slot.sessionsHistory,
            ),
        )
    }

    /** The slot with its current session appended to its history, once. */
    fun remembered(slot: TeamSlot): TeamSlot {
        val session = slot.session
        if (session == null || session in slot.sessionsHistory) return slot
        return slot.copy(sessionsHistory = slot.sessionsHistory + session)
    }

    fun mintId(): String = "team-" + UUID.randomUUID().toString().replace("-", "").take(ID_HEX_CHARS)

    /** Refuses binding a session to [saved] that another unarchived team already holds (Marlin, 2026-10-10:
     *  one active team per session), naming that team. Only a session [saved] did not hold in [before] is
     *  judged, so a team already double-bound in older data can still be edited, and an archived team
     *  frees its sessions. The console draws a double binding on both teams with the other one named
     *  (it never picks one silently); this is the write that keeps new data from making one. */
    fun refuseTaken(all: List<Team>, before: Team?, saved: Team) {
        if (saved.archived) return
        val held = before?.slots?.mapNotNull { it.session }.orEmpty().toSet()
        val others = all.filter { it.id != saved.id && !it.archived }
        for (session in saved.slots.mapNotNull { it.session }.filter { it !in held }) {
            val owner = others.firstOrNull { team -> team.slots.any { it.session == session } } ?: continue
            throw TeamRefusal(
                "session $session is already on the team ${owner.name} (${owner.id}); a session belongs to one " +
                    "active team, so unbind it there or archive that team first",
            )
        }
    }

    /** Refuses a team with no name, a slot with no id or head, a repeated slot id, or a repo that
     *  is set but is not a real directory: the console cannot stat paths, so a refusal here is the
     *  only one a team with a dead repo ever gets. A blank repo (none set) is not checked — this is
     *  the write that SETS a repo, not every write a team's row ever takes. */
    fun validate(team: Team, repoProbe: RepoProbe) {
        val ids = team.slots.map { it.id }
        val reason = when {
            team.name.isBlank() -> "a team needs a name"
            ids.any { it.isBlank() } -> "every slot needs an id"
            ids.toSet().size != ids.size -> "slot ids repeat in ${team.name}"
            team.slots.any { it.head.isBlank() } -> "every slot needs a head"
            else -> repoReason(team, repoProbe)
        }
        if (reason != null) throw TeamRefusal(reason)
    }

    /** Why [team]'s repo is refused, or null when it is blank (unset) or a real directory. */
    private fun repoReason(team: Team, repoProbe: RepoProbe): String? {
        if (team.repo.isBlank()) return null
        return when (repoProbe(team.repo)) {
            RepoPathCheck.MISSING -> "repo '${team.repo}' does not exist"
            RepoPathCheck.NOT_A_DIRECTORY -> "repo '${team.repo}' is not a directory"
            RepoPathCheck.DIRECTORY -> null
        }
    }

    private fun stampOf(before: TeamSlot?, slot: TeamSlot): Long? =
        before?.instructionsUpdatedAt ?: slot.instructionsUpdatedAt
}
