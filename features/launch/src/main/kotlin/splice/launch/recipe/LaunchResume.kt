// NEW: what a launch resumes, out of LaunchService (concentration, 2026-10-27): a bare -c bounded to the head's own
// newest session (V4-183), a `-r SESSION_ID` another head wrote copied in (V4-115), a `-r NAME` of such a session
// read as the id it stands for (Nov 17, 2026), and the sentence the operator is told about each.
package splice.launch.recipe

import splice.client.resume.CallingRoster
import splice.client.resume.ContinueResolution
import splice.client.resume.HeadBoundedContinue
import splice.client.resume.MovingTrust
import splice.client.resume.NamedSession
import splice.client.resume.NamedSessions
import splice.client.resume.ResumeAcrossHeads
import splice.client.resume.SessionAdoption
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.launch.LaunchSpec
import java.nio.file.Path

/** One launch's resume words, read against the head's own index ([bounded]), the other heads' trees ([adopt]) and
 *  the moves in flight ([movingFolder]). */
internal class LaunchResume(
    private val resumeAcrossHeads: ResumeAcrossHeads,
    private val moving: MovingTrust,
) {
    /** V4-183: a bare -c resolves to this head's own newest session in the launch cwd. */
    private val headBoundedContinue = HeadBoundedContinue()

    private val named = NamedSessions()
    private val log = LogSink(DaemonLog::write)

    // why: the shape of the id Claude Code gives every session; a -r word of another shape is a name or a search
    private val sessionUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun bounded(ownTree: Path, extraArgs: List<String>, cwd: String?): ContinueResolution =
        headBoundedContinue.resolve(ownTree, extraArgs, cwd)

    /** V4-169: a session another head wrote goes on as the copy the client resumes. */
    fun resumingAs(args: List<String>, adoption: SessionAdoption?): List<String> =
        headBoundedContinue.resumingAs(args, (adoption as? SessionAdoption.Copied)?.copyId)

    /** The folder the move of this launch's session is in flight out of, or null when it names no session or no
     *  move of its is in flight. */
    fun movingFolder(extraArgs: List<String>): Path? =
        headBoundedContinue.requestedSessionId(extraArgs)?.let(moving::folderOf)

    /** Resolve a launch's `-r SESSION_ID` against the other heads' transcript trees, then plain claude's (V4-115). Null
     *  when the launch named no id — which is every `-c`, every plain launch, and every `-r` with NO
     *  id: the picker is head-bounded by construction, and this is where that stays true. */
    fun adopt(spec: LaunchSpec, extraArgs: List<String>, cwd: String?): SessionAdoption? {
        val sessionId = headBoundedContinue.requestedSessionId(extraArgs) ?: return null
        return resumeAcrossHeads.adopt(
            spec.trees.own,
            spec.trees.siblings + listOfNotNull(spec.trees.vanilla),
            sessionId,
            roster(spec, cwd),
        )
    }

    private fun roster(spec: LaunchSpec, cwd: String?): CallingRoster = CallingRoster(
        spec.models.pinnedModel,
        spec.models.availableModelIds.takeUnless { spec.gateway.clientModels },
        spec.signIn.headKey.ifBlank { null },
        cwd,
    )

    /** Nov 16, 2026: a `-r` word that is a session's NAME is Claude Code's to resolve, and it resolves it among every
     *  head's sessions (the shared tree, V4-168). A session whose answers this head would have to move was then opened
     *  where it lay: `claude -r NAME` on a session another command wrote kept asking for that command's model, the
     *  provider refused every turn, and two clients wrote one transcript. Nov 16 refused such a launch with the id its
     *  copy is made by, which left the person a second command to type. Since Nov 17 the name is read here as the id
     *  it stands for, and the launch goes on as [adopt] makes of that id:
     *  - the one session of the name, with answers to move: its id, so it goes on as its copy;
     *  - several sessions of the name, one of them with no answer to move: that one's id. It is this command's own
     *    line of the work, the copy an earlier launch made among them, and the others are another command's.
     *  [extraArgs] as given for every other launch: an id, the picker, a name whose one session's answers all stay, a
     *  name no session of the folder carries, several sessions this command can all go on with or none of which it
     *  can (the client's picker asks which), and a shim that sent no cwd. */
    fun named(spec: LaunchSpec, extraArgs: List<String>, cwd: String?): List<String> {
        val word = headBoundedContinue.requestedSessionId(extraArgs)?.takeUnless(sessionUuid::matches)
        val folder = cwd?.takeIf { it.isNotBlank() }
        val carrying = word?.let { name -> folder?.let { named.all(spec.trees.own, it, name) } }.orEmpty()
            .filter { sessionUuid.matches(it.id) }
        // a transcript that cannot be read for its models is the client's to open, after its own restore notice
        val moves = carrying.map { moving(it, spec, cwd) ?: return extraArgs }
        val staying = carrying.filterIndexed { at, _ -> moves[at] == 0 }
        val meant = when {
            carrying.size == 1 && staying.isEmpty() -> carrying.single()
            carrying.size > 1 && staying.size == 1 -> staying.single()
            else -> null
        }
        return headBoundedContinue.resumingAs(extraArgs, meant?.id)
    }

    /** How many of [session]'s answers this command would have to move, or null where its transcript cannot be read
     *  for its models, which is said once in the log. */
    private fun moving(session: NamedSession, spec: LaunchSpec, cwd: String?): Int? =
        Cancellables.runCatchingCancellable {
            resumeAcrossHeads.rewriter.pending(session.transcript, roster(spec, cwd))
        }.onFailure { cause ->
            log(
                "[resume] session ${LogSafe.str(session.id)} could not be read for its models " +
                    "(${LogSafe.str(SafeFailureText.render(cause))}); " +
                    "its name goes to the client as typed\n",
            )
        }.getOrNull()

    /** THE RETRY LAW: no refusal is bare and none invents a cause. Each sentence says what happened,
     *  what is still true (nothing copied / that tree untouched), and the operator's next action. */
    fun warning(adoption: SessionAdoption?): String? = when (adoption) {
        // a word no session id can be (a name with a space, a search word with a dot) is Claude Code's to resolve too
        null, is SessionAdoption.HeadOwned, is SessionAdoption.Invalid ->
            null
        is SessionAdoption.Copied ->
            "session ${adoption.sessionId} goes on here as its copy ${adoption.copyId}, with " +
                "${adoption.modelsRewritten} assistant rows on this head's model; the original is left as it was"
        is SessionAdoption.Adopted ->
            "resumed session ${adoption.sessionId} copied into this head from ${adoption.from}, and " +
                "${adoption.modelsRewritten} assistant rows now name this head's model"
        // Claude Code resumes a session by the name -n gave it, or opens its picker on a search word, so a -r
        // word that is not an id is its to resolve, never a refusal ("alpha-studio" was warned as an id)
        is SessionAdoption.Absent -> adoption.takeIf { sessionUuid.matches(it.sessionId) }?.let {
            "session ${it.sessionId} is in no transcript tree: searched " +
                "${it.searchedHeads.size} heads (${it.searchedHeads.joinToString(", ")}). " +
                "Nothing was copied, so Claude Code will refuse the id: check the id, or start a new " +
                "session in this head"
        }
        is SessionAdoption.Empty ->
            "session ${adoption.sessionId}'s transcript exists but is empty at ${adoption.transcript}. " +
                "Nothing was copied; choose another session or start a new one"
        is SessionAdoption.Refused ->
            "session ${adoption.sessionId} could not be copied out of ${adoption.from} " +
                "(${adoption.cause}). That tree is untouched: retry the launch, or resume the session " +
                "on the head that owns it"
    }
}
