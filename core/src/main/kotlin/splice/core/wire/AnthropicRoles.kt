package splice.core.wire

/** The role word of an assistant message on the Anthropic wire: SSE `message_start`, the non-stream terminal and a
 *  passthrough re-anchor's prefill all say it. A Responses input item has its own constant in that dialect. */
public const val ANTHROPIC_ASSISTANT_ROLE: String = "assistant"
