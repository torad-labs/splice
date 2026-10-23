// NEW: (HD-24) the vendor quirk profile lifted out of ChatRequestBuilder.kt — a config value type
// read by three modules (Daemon constructs and overlays it, OpenAiChatProvider holds it, the
// builder reads it), not builder state. The dialect package owns the profile.
package splice.dialect.chat

import splice.core.turn.SpliceNotice
import splice.core.wire.ContentBlock
import splice.core.wire.DocumentBlock
import splice.core.wire.ImageBlock
import splice.core.wire.ThinkingBlock

/** The role word of an assistant message, the one role whose reasoning is replayed. */
private const val ASSISTANT: String = "assistant"

/** What the vendor accepts on the wire: tools, images, and the image-edge floor. */
public data class ChatCapabilityQuirks(
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = true,
    /**
     * DR-155: the vendor's minimum image edge in pixels, or null for "this backend has no stated
     * minimum". NULL IS THE DEFAULT AND THE DEFAULT MATTERS: with it, no outbound image is ever
     * decoded and every non-opted provider's request bytes are identical to before this knob
     * existed. Set only where a vendor documents and ENFORCES a floor — xAI 400s the whole turn
     * with code=invalid_image on anything under 8px per edge, which cost six live turns in the
     * DR-152 soak. See [splice.core.media.ImageFloor] for why every unknown forwards.
     */
    val minImageEdgePx: Int? = null,
)

/** How the vendor takes reasoning effort. */
public data class ChatReasoningQuirks(
    /**
     * When true, emit `reasoning_effort` (and/or `reasoning`) from Anthropic thinking budget so
     * DeepSeek/xAI-compatible chat backends return `reasoning_content` in the stream.
     */
    val emitReasoningEffort: Boolean = true,
    /** Models that accept reasoning_effort=xhigh. null = never emit xhigh (unknown vendors). */
    val xhighModels: Regex? = null,
    /** Opt-in client effort aliases. null preserves the existing thinking-budget tiers. */
    val effortVocabulary: ChatEffortVocabulary? = null,
    /** Send each earlier assistant turn's thinking back as its `reasoning_content`, which llama-server hands the
     *  model's chat template. On for a local runtime (the app's QuirksOverlay), off for a hosted vendor. */
    val replayReasoning: Boolean = false,
)

public data class ChatQuirks(
    val providerTag: String,
    val capabilities: ChatCapabilityQuirks = ChatCapabilityQuirks(),
    /** Some vendors want `max_completion_tokens`, most want `max_tokens`. */
    val maxTokensField: String = "max_tokens",
    val reasoning: ChatReasoningQuirks = ChatReasoningQuirks(),
    /** When set, prompt_cache_key = "<prefix>:<sessionId>" rides every request (server-side
     *  session cache pinning; null = field omitted for vendors of unknown tolerance). */
    val sessionCacheKeyPrefix: String? = null,
    /** Emit stream_options.include_usage (usage frames are opt-in on OpenAI-compat streams). */
    val emitUsageInStream: Boolean = false,
) {
    /** Overlay TOML `[providers.*.quirks].reasoning_effort` onto a chat-dialect quirk profile — null
     *  keeps the provider's own default (see [ChatReasoningQuirks.emitReasoningEffort]). A member rather than the
     *  file-level extension it used to be (Kotlin main sources carry no top-level functions); the
     *  receiver was already a ChatQuirks, so every call site is unchanged. */
    public fun withReasoningEffortToml(reasoningEffort: Boolean?): ChatQuirks =
        copy(reasoning = reasoning.copy(emitReasoningEffort = reasoningEffort ?: reasoning.emitReasoningEffort))

    /** V4-163: overlay `[providers.*.quirks].stream_usage`, or a caller's own default for a provider
     *  kind, onto the profile — null keeps this profile's own value (see [emitUsageInStream]), which
     *  is what keeps every unopted head's request bytes identical. */
    public fun withStreamUsageToml(streamUsage: Boolean?): ChatQuirks =
        copy(emitUsageInStream = streamUsage ?: this.emitUsageInStream)

    /** Overlay a provider kind's own default (on for a local runtime) onto the profile. null keeps this profile's own
     *  value, which keeps every hosted head's request bytes. */
    public fun withReplayReasoning(replayReasoning: Boolean?): ChatQuirks =
        copy(reasoning = reasoning.copy(replayReasoning = replayReasoning ?: reasoning.replayReasoning))

    /**
     * The reasoning an earlier assistant turn carried, for a profile that replays it (a self-served runtime, whose
     * chat template renders it inside that turn's think block): the turn's thinking blocks in order with nothing added
     * between, which is the one reasoning run the chat stream wrote as consecutive deltas. Splice's own wait notice is
     * a thinking block on the client's history too, and never the model's reasoning, so it is left out. Null when the
     * profile does not replay or the turn holds none, so a missing reasoning stays missing on the wire.
     */
    internal fun replayedReasoning(role: String, content: List<ContentBlock>): String? {
        if (!reasoning.replayReasoning || role != ASSISTANT) return null
        return content.filterIsInstance<ThinkingBlock>()
            .filter { it.signature != SpliceNotice.SIGNATURE }
            .joinToString("") { it.thinking }
            .ifEmpty { null }
    }

    /** Honest markers for content this dialect cannot carry: documents always; images when the
     *  vendor has no vision. An image-only message still yields a marker — silently dropping the
     *  whole message breaks role alternation AND hides the omission from the model. */
    public fun omissionMarkers(content: List<ContentBlock>): List<String> {
        val out = mutableListOf<String>()
        content.filterIsInstance<DocumentBlock>().forEach { _ ->
            out.add("[document omitted by $providerTag proxy: unsupported on this backend]")
        }
        if (!capabilities.supportsVision) {
            val n = content.count { it is ImageBlock }
            if (n > 0) out.add("[$n image(s) omitted by $providerTag proxy: backend has no vision]")
        }
        return out
    }
}
