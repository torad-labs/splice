// NEW: (HD-24) the vendor quirk profile lifted out of ChatRequestBuilder.kt — a config value type
// read by three modules (Daemon constructs and overlays it, OpenAiChatProvider holds it, the
// builder reads it), not builder state. The dialect package owns the profile.
package splice.dialect.chat

public data class ChatQuirks(
    val providerTag: String,
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = true,
    /** Some vendors want `max_completion_tokens`, most want `max_tokens`. */
    val maxTokensField: String = "max_tokens",
    /**
     * When true, emit `reasoning_effort` (and/or `reasoning`) from Anthropic thinking budget so
     * DeepSeek/xAI-compatible chat backends return `reasoning_content` in the stream.
     */
    val emitReasoningEffort: Boolean = true,
    /** When set, prompt_cache_key = "<prefix>:<sessionId>" rides every request (server-side
     *  session cache pinning; null = field omitted for vendors of unknown tolerance). */
    val sessionCacheKeyPrefix: String? = null,
    /** Emit stream_options.include_usage (usage frames are opt-in on OpenAI-compat streams). */
    val emitUsageInStream: Boolean = false,
) {
    /** Overlay TOML `[providers.*.quirks].reasoning_effort` onto a chat-dialect quirk profile — null
     *  keeps the provider's own default (see [emitReasoningEffort]). A member rather than the
     *  file-level extension it used to be (Kotlin main sources carry no top-level functions); the
     *  receiver was already a ChatQuirks, so every call site is unchanged. */
    public fun withReasoningEffortToml(reasoningEffort: Boolean?): ChatQuirks =
        copy(emitReasoningEffort = reasoningEffort ?: this.emitReasoningEffort)
}
