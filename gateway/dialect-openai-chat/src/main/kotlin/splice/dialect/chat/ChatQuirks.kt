// NEW: (HD-24) the vendor quirk profile lifted out of ChatRequestBuilder.kt — a config value type
// read by three modules (Daemon constructs and overlays it, OpenAiChatProvider holds it, the
// builder reads it), not builder state. The dialect package owns the profile.
package splice.dialect.chat

import splice.core.wire.ContentBlock
import splice.core.wire.DocumentBlock
import splice.core.wire.ImageBlock

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

    /** Honest markers for content this dialect cannot carry: documents always; images when the
     *  vendor has no vision. An image-only message still yields a marker — silently dropping the
     *  whole message breaks role alternation AND hides the omission from the model. */
    public fun omissionMarkers(content: List<ContentBlock>): List<String> {
        val out = mutableListOf<String>()
        content.filterIsInstance<DocumentBlock>().forEach { _ ->
            out.add("[document omitted by $providerTag proxy: unsupported on this backend]")
        }
        if (!supportsVision) {
            val n = content.count { it is ImageBlock }
            if (n > 0) out.add("[$n image(s) omitted by $providerTag proxy: backend has no vision]")
        }
        return out
    }
}
