// NEW: Oct 13, 2026 (marlin's call on web-console §4.13 "Bind sessions ... or by rule", Marcos's ask of 2026-09-17,
// via splice-lead) — a session someone starts in a team's repo on a slot's head joins that slot without a pick.
//
// THE RULE IS THE SLOT'S. A slot with bind_by_rule set takes a session that starts on the slot's head in a folder at or
// under its team's repo. Nothing else is judged: the head is the one whose SessionStart hook fired, and the folder is
// the one Claude Code reports to that hook.
//
// WHEN: at the session's start only, through that hook, before its first turn, so the first turn already carries the
// role (SlotInstructions). A resume keeps whatever binding the session had.
//
// WHICH SLOT. Unarchived teams in created order, their slots in order: the first rule slot on that head whose team's
// repo holds the folder and that is free. Free is no session, or one the registry no longer runs. A running member is
// never displaced, and with no registry to ask, only a slot with no session is free. A session already on an active
// team stays where it is (one active team per session, Marlin 2026-10-10).
//
// NEVER A FAILURE OF THE START. The binding is the store's own bind, under the store's lock so two starts cannot take
// one slot. A refusal comes back as a log line, and the session starts either way.
package splice.sessions.teams

import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionSource
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

public class TeamRuleBinding(private val store: TeamStore, private val sessions: SessionSource?) {

    /** The log line saying which slot [session] joined, or why it could not; null when no rule takes it. */
    public fun started(head: String, session: String, cwd: String): String? = synchronized(store) {
        val folder = canonical(cwd)
        val onTeam = store.bindingsOf(session).any { (team, _) -> !team.archived }
        val taker = if (folder == null || onTeam) null else taker(head, folder)
        taker?.let { (team, slot) ->
            Cancellables.runCatchingCancellable { store.bind(team.id, mapOf(slot.id to session)) }
                .fold(
                    { "session $session joined the ${slot.id} slot of ${team.name} by its rule" },
                    {
                        "session $session could not join the ${slot.id} slot of ${team.name} " +
                            "(${SafeFailureText.render(it)})"
                    },
                )
        }
    }

    /** The first free rule slot on [head] whose team's repo holds [folder], with its team. */
    private fun taker(head: String, folder: Path): Pair<Team, TeamSlot>? {
        // read only when a rule slot on this head has a session to judge: most starts never touch the registry
        val running by lazy {
            sessions?.read()?.filter { it.availability != SessionAvailability.GONE }
                ?.mapNotNull { it.sessionId }?.toSet()
        }
        return store.teams().asSequence()
            .filter { !it.archived && it.repo.isNotBlank() && canonical(it.repo)?.let(folder::startsWith) == true }
            .flatMap { team -> team.slots.asSequence().map { team to it } }
            .firstOrNull { (_, slot) ->
                slot.bindByRule && slot.head == head &&
                    slot.session.let { it == null || running?.contains(it) == false }
            }
    }

    /** [path] as the filesystem spells it, links resolved, so a repo and a folder reached through a link still match;
     *  as written when it cannot be resolved, and null when it is not a path at all. */
    private fun canonical(path: String): Path? = try {
        val given = Paths.get(path).toAbsolutePath().normalize()
        try {
            given.toRealPath()
        } catch (_: IOException) {
            given
        }
    } catch (_: InvalidPathException) {
        null
    }
}
