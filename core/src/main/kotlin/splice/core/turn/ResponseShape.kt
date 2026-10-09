package splice.core.turn

/** What the completed upstream response held. */
public data class ResponseShape(
    /** A `message` output item COMPLETED this round, whether or not it carried text.
     *
     *  An empty message is the model's finished answer, not an absence: given nothing to add
     *  (Astra after a Stop hook echoes an end-of-turn report back at it, 2026-09-05) it closes
     *  a message with no text, exactly as codex renders — codex ends the turn there, since
     *  only a tool call sets `needs_follow_up`. Grading that turn `empty_model` handed Claude
     *  Code an API error it retried a dozen times per incident, so the empty-turn gate reads
     *  this first: a closed message ends clean; a round with NO message item stays the honest
     *  error it always was. */
    val messageClosed: Boolean = false,
    /** What the completed upstream response actually held, item by item, in one short line
     *  (`status=completed items=[reasoning(summary=0,enc=1842) message(output_text:0)]`).
     *  Evidence only: read by the empty-turn line so a "no content" verdict names the shape
     *  the backend sent instead of asserting an absence nobody can grep. */
    val outputShape: String = "",
)
