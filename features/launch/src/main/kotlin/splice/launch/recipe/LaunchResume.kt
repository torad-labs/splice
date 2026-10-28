// NEW: what a launch resumes, out of LaunchService (concentration, 2026-10-27): a bare -c bounded to the head's own
// newest session (V4-183), a `-r SESSION_ID` another head wrote copied in (V4-115), and the sentence the operator is
// told about it.
package splice.launch.recipe

import splice.client.resume.CallingRoster
import splice.client.resume.ContinueResolution
import splice.client.resume.HeadBoundedContinue
import splice.client.resume.MovingTrust
import splice.client.resume.ResumeAcrossHeads
import splice.client.resume.SessionAdoption
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

    // why: the shape of the id Claude Code gives every session; a -r word of another shape is a name or a search
    private val sessionUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun bounded(ownTree: Path, extraArgs: List<String>, cwd: String?): ContinueResolution =
        headBoundedContinue.resolve(ownTree, extraArgs, cwd)

    /** V4-169 (the President, Oct 13): a session another head wrote goes on as the copy the client resumes. */
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
            CallingRoster(
                spec.models.pinnedModel,
                spec.models.availableModelIds.takeUnless { spec.gateway.clientModels },
                spec.signIn.headKey.ifBlank { null },
                cwd,
            ),
        )
    }

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
        // word that is not an id is its to resolve, never a refusal (Oct 12: "temple-studio" was warned as an id)
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
