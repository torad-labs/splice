// NEW: the Context Getter (docs/specs/harness.md, decision 9). The gatherer's input: the session's first user message as
// the client sent it, inside a prompt that asks for what another agent needs to start on that request, as facts with
// their locations, changing nothing and not doing the task.
package splice.harness

internal object GatheringPrompt {
    private val asked =
        """
        |Another agent is about to start on the request below, in this folder. Explore the folder and gather what that
        |agent needs to start on it, so it does not have to look for it itself:
        |
        |- the files that matter for this request, with their paths and the line ranges that matter;
        |- the entry points the request touches;
        |- how the project builds and runs its tests, with the exact commands;
        |- what in the repository's own instructions (CLAUDE.md, AGENTS.md, CONTRIBUTING and the like) applies to it.
        |
        |Give facts with their locations, not advice. Change nothing, and do not do the task: only gather. Answer with
        |what you found and nothing else.
        |
        |The request:
        """.trimMargin()

    /** The prompt around [firstUserMessage], which goes in after the margin is trimmed, so a line of it that starts
     *  with `|` stays as the person wrote it. */
    fun of(firstUserMessage: String): String = "$asked\n\n<request>\n$firstUserMessage\n</request>\n"
}
