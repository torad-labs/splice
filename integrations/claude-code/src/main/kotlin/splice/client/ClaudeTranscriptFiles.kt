// NEW: Claude Code owns its transcript layout; consumers receive these facts without copying them.
package splice.client

import splice.client.resume.TRANSCRIPT_SUFFIX

/** Claude Code's transcript directory and filename format, shared with launch and session composition. */
public object ClaudeTranscriptFiles {
    /** Directory under a Claude Code state tree containing its working-directory transcript folders. */
    public val projectsDirectory: String = Keys.PROJECTS

    /** Extension of a session transcript, including its leading dot. */
    public val suffix: String = TRANSCRIPT_SUFFIX
}
