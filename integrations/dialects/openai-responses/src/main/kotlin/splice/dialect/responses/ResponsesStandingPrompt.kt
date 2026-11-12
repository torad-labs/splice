// NEW (Marcos, 2026-10-28): the standing instructions the OpenAI Responses adapter ships beside Claude Code's own
// prompt, for a head that sets none of its own. Claude Code's prompt stays (Marcos: GPT does best under it, on the
// benchmarks); this covers what it leaves unsaid for a model that is not Claude, from GPT's claudex transcripts of Oct:
// ending turns before the work is done, reading far more than the work needs, and polling what has not changed.
package splice.dialect.responses

/** The bundled standing prompt, as the jar carries it in splice/dialect/responses/standing-prompt.md. */
public class ResponsesStandingPrompt {
    public fun text(): String = checkNotNull(javaClass.getResourceAsStream(STANDING_PROMPT_RESOURCE)) {
        "missing the bundled Responses standing prompt: $STANDING_PROMPT_RESOURCE"
    }.bufferedReader(Charsets.UTF_8).use { it.readText().trimEnd() }
}

private const val STANDING_PROMPT_RESOURCE = "/splice/dialect/responses/standing-prompt.md"
