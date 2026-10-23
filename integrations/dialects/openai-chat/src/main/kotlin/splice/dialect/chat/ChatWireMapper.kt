// NEW: (HD-24) OpenAI-chat message and media mapping, lifted out of ChatRequestBuilder.kt.
// Adjacency stays here: tool messages immediately follow assistant.tool_calls; tool_result
// images follow the whole tool block. ChatToolInput owns tool definitions and encoded arguments,
// using the shared wire vocabulary below. imagePart/omissionMarkers share quirks.capabilities.supportsVision
// so the v25 omission markers cannot drift.
//
// DR-155 adds a SECOND drop gate beside supportsVision: the vendor minimum-edge floor. It is
// deliberately applied BEFORE imagePart rather than inside it, so imagePart's null keeps meaning
// exactly the three things it already meant and the new policy carries its own count and its own
// sentence — see [belowFloor].
package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.media.ImageFloor
import splice.core.turn.SpliceNotice
import splice.core.wire.AnthropicRequest
import splice.core.wire.ImageBlock
import splice.core.wire.MediaSource
import splice.core.wire.TextBlock
import splice.core.wire.ThinkingBlock
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock
import splice.core.wire.UnknownBlock
import splice.upstream.memory.MessageFragments

internal class ChatWireMapper(private val quirks: ChatQuirks) {

    private val floor = ImageFloor(quirks.capabilities.minImageEdgePx)
    private val toolInput = ChatToolInput()
    private val fragments = MessageFragments()

    fun messagesArray(system: String?, body: AnthropicRequest, context: String? = null): JsonArray = buildJsonArray {
        // No system prompt, no system message — on every turn, compaction included.
        system?.let { sys ->
            addJsonObject {
                put(ROLE, "system")
                put(CONTENT, sys)
            }
        }
        val alternation = ChatAlternation()
        body.messages.forEach { msg ->
            val mapped = if (context == null) {
                buildJsonArray { appendMessage(this, msg.role, msg.content) }
            } else {
                fragments.message(context, msg) { buildJsonArray { appendMessage(this, msg.role, msg.content) } }
            }
            // Merging across dropped messages is request-local, never part of a cached fragment.
            alternation.accept(mapped.filterIsInstance<JsonObject>())
        }
        alternation.messages().forEach { add(it) }
    }

    /** A block kind splice does not enumerate cannot ride this wire; it leaves a marker, never a silent drop.
     *  A tool_reference says its tool is loaded: this wire sends the client's whole tool list whenever the head
     *  takes tools at all, so there the sentence is true. */
    private fun unknownBlockMarkers(content: List<splice.core.wire.ContentBlock>): List<String> =
        content.filterIsInstance<UnknownBlock>()
            .map { it.omissionMarker(quirks.providerTag, toolsCallable = quirks.capabilities.supportsTools) }

    // the content-block split is the mapping contract
    fun appendMessage(
        sink: JsonArrayBuilder,
        role: String,
        content: List<splice.core.wire.ContentBlock>,
    ) {
        // tool_result blocks become their own `tool` role messages; text/images fold into one.
        val toolResults = content.filterIsInstance<ToolResultBlock>()
        val toolUses = content.filterIsInstance<ToolUseBlock>()
        // Dropped media leaves an HONEST MARKER (the v25 doctrine: screenshots silently
        // vanishing is the regression class; the model must know something was omitted).
        val markers = quirks.omissionMarkers(content) + unknownBlockMarkers(content)
        val imageBlocks = content.filterIsInstance<ImageBlock>()
        // DR-155: split off the images a vendor floor PROVES are undersized before anything maps
        // them, so the two drop reasons stay two reasons. `mappable` is what the old code saw.
        val (undersized, mappable) = imageBlocks.partition { belowFloor(it.source) != null }
        val images = mappable.mapNotNull { imagePart(it.source) }
        // DR-94: vision-ON drops (a source imagePart cannot map) escaped the omissionMarkers gate,
        // which keys on supportsVision alone — an image-only message then vanished ENTIRELY,
        // breaking role alternation and hiding the loss from the model. Marker on the DELTA the
        // mapping actually produced; the no-vision case stays omissionMarkers' (one marker, not two).
        val sourceMarkers = unreadableSourceMarkers(mappable.size - images.size) + floorMarkers(undersized)
        val textsRaw = content.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
        val texts = (listOf(textsRaw) + markers + sourceMarkers).filter { it.isNotEmpty() }.joinToString("\n")
        val reasoning = replayedReasoning(role, content)

        if (toolUses.isNotEmpty()) {
            appendAssistantToolCalls(sink, toolUses, texts, reasoning)
        }
        // A `tool` message must immediately follow the assistant message that carries its
        // tool_call_ids. Claude Code packs [tool_result, text] into one user message, so emit the
        // tool results BEFORE any sibling user text/images — an interposed `user` message is a 400
        // on strict OpenAI-compatible validators (and reorders the turn semantically everywhere).
        appendToolResults(sink, toolResults)
        val hasUserPayload = texts.isNotEmpty() || images.isNotEmpty()
        // A turn that only reasoned (cut off before it answered) is still a turn the model took, so with its
        // reasoning replayed it keeps its place instead of leaving the gap ChatAlternation closes.
        val hasMessage = hasUserPayload || reasoning != null
        if (toolUses.isEmpty() && hasMessage) {
            appendUserContent(sink, role, texts, images, reasoning)
        }
    }

    /**
     * The reasoning an earlier assistant turn carried, for a provider that replays it (a self-served runtime, whose
     * chat template renders it inside that turn's think block): the turn's thinking blocks in order with nothing added
     * between, which is the one reasoning run the chat stream wrote as consecutive deltas. Splice's own wait notice is
     * a thinking block on the client's history too, and never the model's reasoning, so it is left out. Null when the
     * provider does not replay or the turn holds none, so a missing reasoning stays missing on the wire.
     */
    private fun replayedReasoning(role: String, content: List<splice.core.wire.ContentBlock>): String? {
        if (!quirks.reasoning.replayReasoning || role != ASSISTANT_ROLE) return null
        return content.filterIsInstance<ThinkingBlock>()
            .filter { it.signature != SpliceNotice.SIGNATURE }
            .joinToString("") { it.thinking }
            .ifEmpty { null }
    }

    fun appendAssistantToolCalls(
        sink: JsonArrayBuilder,
        toolUses: List<ToolUseBlock>,
        texts: String,
        reasoning: String? = null,
    ) = toolInput.appendCalls(sink, toolUses, texts, reasoning)

    fun appendUserContent(
        sink: JsonArrayBuilder,
        role: String,
        texts: String,
        images: List<JsonObject>,
        reasoning: String? = null,
    ) {
        sink.addJsonObject {
            put(ROLE, role)
            if (images.isEmpty()) {
                put(CONTENT, texts)
            } else {
                put(
                    CONTENT,
                    buildJsonArray {
                        if (texts.isNotEmpty()) {
                            addJsonObject {
                                put(TYPE, TEXT)
                                put(TEXT, texts)
                            }
                        }
                        images.forEach { add(it) }
                    },
                )
            }
            reasoning?.let { put(REASONING_CONTENT, it) }
        }
    }

    fun appendToolResults(
        sink: JsonArrayBuilder,
        toolResults: List<ToolResultBlock>,
    ) {
        // Emit ALL tool messages first (OpenAI adjacency: every tool must immediately follow
        // assistant.tool_calls with no intervening user). Image follow-ups land AFTER the full
        // tool block — inserting user(images) between parallel tools 400s strict validators.
        val trailingImages = mutableListOf<Pair<String, List<JsonObject>>>()
        toolResults.forEach { tr ->
            // A nested block kind splice does not enumerate leaves its marker in the output, never an empty result.
            val out = (tr.content.filterIsInstance<TextBlock>().map { it.text } + unknownBlockMarkers(tr.content))
                .joinToString("\n")
            // DR-155: the same pre-split as the message path. A tool_result carrying a screenshot
            // AND a favicon-sized image is the realistic case, and it must say both things.
            val (undersized, mappable) = tr.content.filterIsInstance<ImageBlock>()
                .partition { belowFloor(it.source) != null }
            val images = mappable.mapNotNull { imagePart(it.source) }
            val dropped = mappable.size - images.size
            sink.addJsonObject {
                put(ROLE, "tool")
                put("tool_call_id", tr.toolUseId)
                // string-only channel: dropped images (no vision, an unreadable source, or a vendor
                // minimum edge) are declared IN the output — on the DELTA, so a partial drop is
                // marked too (DR-94), each reason keeping its own sentence (DR-155).
                put(CONTENT, markerFold(out, dropped, undersized))
            }
            if (images.isNotEmpty()) {
                trailingImages.add(tr.toolUseId to images)
            }
        }
        // v25 doctrine: chat `tool` messages are string-only, so tool_result images ride
        // follow-up user messages after the contiguous tool block (Responses parity).
        trailingImages.forEach { (toolUseId, images) ->
            appendUserContent(sink, "user", "[images from tool_result $toolUseId]", images)
        }
    }

    fun markerFold(out: String, imageCount: Int, undersized: List<ImageBlock>): String {
        // DR-94: with vision ON the drop was an unreadable SOURCE, not a capability gap — the
        // marker must not blame vision the backend has.
        val reason = if (quirks.capabilities.supportsVision) "unreadable image source" else "backend has no vision"
        val unreadable = if (imageCount > 0) {
            listOf("[$imageCount image(s) omitted by ${quirks.providerTag} proxy: $reason]")
        } else {
            emptyList()
        }
        return (listOf(out) + unreadable + floorMarkers(undersized))
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    // DR-94: the vision-ON drop marker (empty base64, unknown source type). Distinct from
    // omissionMarkers so the no-vision path never emits two markers for the same images.
    private fun unreadableSourceMarkers(dropped: Int): List<String> =
        if (dropped > 0 && quirks.capabilities.supportsVision) {
            listOf("[$dropped image(s) omitted by ${quirks.providerTag} proxy: unreadable image source]")
        } else {
            emptyList()
        }

    /**
     * DR-155: the vendor-floor marker, with its OWN count and its OWN reason. Relabelling an
     * undersized image as "unreadable image source" would be a lie the operator cannot debug — the
     * source read perfectly, the backend simply refuses images that small — so a message carrying
     * one of each emits TWO markers rather than a merged count.
     */
    private fun floorMarkers(undersized: List<ImageBlock>): List<String> {
        val min = undersized.firstNotNullOfOrNull { belowFloor(it.source) } ?: return emptyList()
        return listOf(
            "[${undersized.size} image(s) omitted by ${quirks.providerTag} proxy: ${floor.reason(min)}]",
        )
    }

    /**
     * The floor a source is proven to violate, or null. Gated on vision: with vision OFF every image
     * is dropped anyway and omissionMarkers already says so once, so probing here could only add a
     * second marker for the same image — the double-marker class DR-94's split exists to prevent.
     */
    private fun belowFloor(source: MediaSource?): Int? {
        if (source == null || !quirks.capabilities.supportsVision) return null
        return floor.violatedMinimum(source)
    }

    fun toolsArray(body: AnthropicRequest) = toolInput.definitions(body)

    fun imagePart(source: MediaSource?): JsonObject? {
        // Bound to a local BEFORE the branch so `isNullOrEmpty`'s contract narrows the type the
        // compiler actually tracks. `source.data` is a property of a class from another module, so
        // a read of it is not smart-cast-stable and the guard above could not carry into the body —
        // that, and only that, is why this used to end in `!!`. Same guard, same value, now proven.
        val data = source?.data
        return when {
            source == null || !quirks.capabilities.supportsVision -> null
            source.type == "base64" && !data.isNullOrEmpty() -> {
                val mime = source.mediaType ?: "image/png"
                val size = DATA_URL_PREFIX.length + mime.length + BASE64_SEPARATOR.length + data.length
                val dataUrl = StringBuilder(size)
                    .append(DATA_URL_PREFIX).append(mime).append(BASE64_SEPARATOR).append(data)
                    .toString()
                buildJsonObject {
                    put(TYPE, IMAGE_URL)
                    put(IMAGE_URL, buildJsonObject { put(URL, dataUrl) })
                }
            }
            source.type == "url" && !source.url.isNullOrEmpty() -> buildJsonObject {
                put(TYPE, IMAGE_URL)
                put(IMAGE_URL, buildJsonObject { put(URL, source.url) })
            }
            else -> null
        }
    }
}

// Chat wire field names — repeated across the message/tool/image mappings.
private const val ROLE = "role"
private const val ASSISTANT_ROLE = "assistant"
internal const val CONTENT = "content" // V4-170: shared with ChatSystemPrompt, the one declaration on this wire

/** An assistant message's reasoning as llama-server reads it from a request (common/chat.cpp). */
internal const val REASONING_CONTENT = "reasoning_content"
internal const val TYPE = "type"
internal const val NAME = "name"
private const val TEXT = "text"
private const val URL = "url"
internal const val FUNCTION = "function"
private const val IMAGE_URL = "image_url"
private const val DATA_URL_PREFIX = "data:"
private const val BASE64_SEPARATOR = ";base64,"
