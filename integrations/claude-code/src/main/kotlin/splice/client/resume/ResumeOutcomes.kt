// Split from ResumeAcrossHeads.kt (Oct 13, concentration law): the two answers a `-r SESSION_ID` resolves to, the plan
// asked without acting and the adoption a launch acts on. ResumeAcrossHeads produces both; recipes, launches and the
// console's resume read them.
package splice.client.resume

import java.nio.file.Path

/** What a launch's `-r SESSION_ID` WOULD resolve to, asked without acting (V4-320): the one resolution
 *  [ResumeAcrossHeads.adopt] acts on, so a recipe that describes a resume and the launch that performs it
 *  cannot disagree about which transcript is taken or where it lands. */
public sealed class ResumePlan {
    /** The id is in the calling head's own tree: it resumes where it lies. */
    public data class Owned(public val transcript: Path) : ResumePlan()

    /** The id is in another head's tree ([fromHead]): the launch copies [from] to [into]. */
    public data class Copy(public val from: Path, public val fromHead: Path, public val into: Path) : ResumePlan()

    /** The primary transcript exists but contains no conversation bytes. */
    public data class Empty(public val sessionId: String, public val transcript: Path) : ResumePlan()

    /** The id is in no head's tree; [searchedHeads] names where it looked. */
    public data class Absent(public val sessionId: String, public val searchedHeads: List<String>) : ResumePlan()

    /** The text is not a session id; the cause never echoes it. */
    public data class Invalid(public val cause: String) : ResumePlan()
}

/** The answer to "where did this launch's `-r SESSION_ID` come from?". A sealed OUTCOME, not an
 *  exception: every refusal is a value the caller's `when` has to handle. */
public sealed class SessionAdoption {
    /** The id is already in the calling head's own tree — nothing was copied. Its assistant rows were
     *  moved onto the head's model in place (V4-169); [modelsRewritten] is 0 when they already named it,
     *  or when the rewrite failed, which is logged and never blocks the launch. */
    public data class HeadOwned(public val transcript: Path, public val modelsRewritten: Int = 0) : SessionAdoption()

    /** A session this head sees through the shared tree but did not write, resumed as its copy [copyId] beside it with
     *  [modelsRewritten] assistant rows on this head's model; the original is left byte for byte (the head-isolation
     *  law's explicit `-r` copy). The launch resumes [copyId]. */
    public data class Copied(
        public val sessionId: String,
        public val copyId: String,
        public val into: Path,
        public val modelsRewritten: Int,
    ) : SessionAdoption()

    /** A foreign transcript was copied in, and its assistant rows were rewritten to the head's model. */
    public data class Adopted(
        public val sessionId: String,
        public val from: Path,
        public val into: Path,
        public val modelsRewritten: Int,
    ) : SessionAdoption()

    /** The primary transcript exists but has no bytes; no adoption was attempted. */
    public data class Empty(public val sessionId: String, public val transcript: Path) : SessionAdoption()

    /** The id is in no head's projects tree. Carries WHICH trees were searched, so the caller can
     *  name them: a refusal that does not say where it looked leaves the operator nothing to do. */
    public data class Absent(
        public val sessionId: String,
        public val searchedHeads: List<String>,
    ) : SessionAdoption()

    /** The id WAS found but the copy or the model rewrite failed. Carries the source tree and the
     *  rendered cause, so the caller can name what failed, where, and what is still true. */
    public data class Refused(
        public val sessionId: String,
        public val from: Path,
        public val cause: String,
    ) : SessionAdoption()

    /** The requested text is not a session id at all. Carries no echo of the rejected argument. */
    public data class Invalid(public val cause: String) : SessionAdoption()
}
