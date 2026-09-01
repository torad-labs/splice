// NEW: (HD-24) the OpenAI-chat wire mapper lifted out of ChatRequestBuilder.kt — the file's real
// centre of gravity: everything here, and only what is here, knows OpenAI-chat wire field NAMES
// and the adjacency invariants (tool messages immediately after assistant.tool_calls; trailing
// tool_result images on a follow-up user message after the whole tool block; HD-20's putFunction
// argument order). imagePart/omissionMarkers share `quirks.supportsVision` — one field, one gate,
// so the v25 omission markers can't drift. toolsArray rides along as the third consumer of the
// same TYPE/FUNCTION/NAME wire vocabulary, avoiding a duplicated constant set.
//
// DR-155 adds a SECOND drop gate beside supportsVision: the vendor minimum-edge floor. It is
// deliberately applied BEFORE imagePart rather than inside it, so imagePart's null keeps meaning
// exactly the three things it already meant and the new policy carries its own count and its own
// sentence — see [belowFloor].
package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.media.ImageFloor
import splice.core.wire.AnthropicRequest
import splice.core.wire.ImageBlock
import splice.core.wire.MediaSource
import splice.core.wire.TextBlock
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock

internal class ChatWireMapper(private val quirks: ChatQuirks) {

    private val floor = ImageFloor(quirks.minImageEdgePx)

    fun messagesArray(system: String?, body: AnthropicRequest): JsonArray = buildJsonArray {
        // CX-02: on a compact turn the directive rides the system message — and a compact turn
        // with no system prompt at all still gets one, because stripping tools alone never told
        // the backend it was summarizing. Non-compact is untouched: no system, no message.
        system?.let { sys ->
            addJsonObject {
                put(ROLE, "system")
                put(CONTENT, sys)
            }
        }
        body.messages.forEach { msg -> appendMessage(this, msg.role, msg.content) }
    }

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
        val markers = quirks.omissionMarkers(content)
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

        if (toolUses.isNotEmpty()) {
            appendAssistantToolCalls(sink, toolUses, texts)
        }
        // A `tool` message must immediately follow the assistant message that carries its
        // tool_call_ids. Claude Code packs [tool_result, text] into one user message, so emit the
        // tool results BEFORE any sibling user text/images — an interposed `user` message is a 400
        // on strict OpenAI-compatible validators (and reorders the turn semantically everywhere).
        appendToolResults(sink, toolResults)
        val hasUserPayload = texts.isNotEmpty() || images.isNotEmpty()
        if (toolUses.isEmpty() && hasUserPayload) {
            appendUserContent(sink, role, texts, images)
        }
    }

    fun appendAssistantToolCalls(
        sink: JsonArrayBuilder,
        toolUses: List<ToolUseBlock>,
        texts: String,
    ) {
        sink.addJsonObject {
            put(ROLE, "assistant")
            if (texts.isNotEmpty()) put(CONTENT, texts) else put(CONTENT, null as String?)
            put(
                "tool_calls",
                buildJsonArray {
                    toolUses.forEach { tu ->
                        addJsonObject {
                            put("id", tu.id)
                            put(TYPE, FUNCTION)
                            putFunction(this, tu.name, tu.input.toString())
                        }
                    }
                },
            )
        }
    }

    fun appendUserContent(
        sink: JsonArrayBuilder,
        role: String,
        texts: String,
        images: List<JsonObject>,
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
            val out = tr.content.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
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
        val reason = if (quirks.supportsVision) "unreadable image source" else "backend has no vision"
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
        if (dropped > 0 && quirks.supportsVision) {
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
        if (source == null || !quirks.supportsVision) return null
        return floor.violatedMinimum(source)
    }

    // ARGUMENT ORDER (HD-20): the former `JsonObjectBuilder` receiver became the first parameter and
    // [name]/[args] kept their order, so the sole call site reads `putFunction(this, tu.name,
    // tu.input.toString())` — the tool NAME still lands on "name" and the serialized input on
    // "arguments". Both are String, so a swap would compile and only show up as a corrupted tool call.
    fun putFunction(sink: JsonObjectBuilder, name: String, args: String) {
        sink.put(
            FUNCTION,
            buildJsonObject {
                put(NAME, name)
                put("arguments", args)
            },
        )
    }

    fun toolsArray(body: AnthropicRequest) = buildJsonArray {
        body.tools.forEach { t ->
            addJsonObject {
                put(TYPE, FUNCTION)
                put(
                    FUNCTION,
                    buildJsonObject {
                        put(NAME, t.name)
                        put("description", t.description ?: "")
                        put("parameters", t.inputSchema ?: buildJsonObject { put(TYPE, "object") })
                    },
                )
            }
        }
    }

    fun imagePart(source: MediaSource?): JsonObject? {
        // Bound to a local BEFORE the branch so `isNullOrEmpty`'s contract narrows the type the
        // compiler actually tracks. `source.data` is a property of a class from another module, so
        // a read of it is not smart-cast-stable and the guard above could not carry into the body —
        // that, and only that, is why this used to end in `!!`. Same guard, same value, now proven.
        val data = source?.data
        return when {
            source == null || !quirks.supportsVision -> null
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
private const val CONTENT = "content"
private const val TYPE = "type"
private const val NAME = "name"
private const val TEXT = "text"
private const val URL = "url"
private const val FUNCTION = "function"
private const val IMAGE_URL = "image_url"
private const val DATA_URL_PREFIX = "data:"
private const val BASE64_SEPARATOR = ";base64,"
