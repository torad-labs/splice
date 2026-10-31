// A tool value enters code mode with every image in it whole and the text beside them bounded as text alone is bounded.
// Before Oct 26, 2026 admission cut the whole value like text, data URLs included, and a native script that passed a
// cut data URL to image() sent the provider an image no provider can read, on that turn and on every later one.
package splice.provider.codex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.upstream.codemode.CodeModeLimits
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeToolOutputs
import java.nio.file.Path
import java.util.Base64
import kotlin.random.Random

private const val SEED = 20_261_026L
private const val CASES = 150

// why: a character cap below the 64 KiB byte cap, so the property holds under either one.
private const val SMALL_CAP = 1_000

// why: images run from a few bytes to about 330,000 base64 chars, text up to 90,000 chars, past the 64 KiB cut.
private const val MAX_IMAGE_BYTES = 250_000
private const val MAX_TEXT = 90_000
private const val NEWLINE_EVERY = 4_000

class CodeModeToolValueAdmissionTest {
    /** For any tool value Claude Code's results project to (text and images in any order and size), admission keeps
     *  every image's data URL whole, in its order, and bounds the text beside them as [CodeModeLimits.boundedText]
     *  bounds that text alone, so a value without images is admitted exactly as before. */
    @Test
    fun `admission keeps every image in a tool value whole and bounds the text beside them as text alone`(
        @TempDir tmp: Path,
    ) {
        val random = Random(SEED)
        listOf(CodeModeScriptBounds(), CodeModeScriptBounds(maxOutputChars = SMALL_CAP)).forEach { bounds ->
            val validation = CodexCodeModeValidation(
                CodeModeBridgeConfig(
                    runtimes = CodeModeRuntimes { error("admission runs no script") },
                    state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
                    bounds = bounds,
                ),
            )
            repeat(CASES) { index ->
                val items = ToolValues.items(random)
                val value = CodeModeToolOutputs.content(items)
                val result = CodeModeResult("toolu_splice_$index", "rendered", payload = JsonPrimitive(value))
                val admitted = checkNotNull(validation.admit(result).payload).jsonPrimitive.content
                val urls = items.mapNotNull(ToolValues::url)
                val what = "seed $SEED, cap ${bounds.maxOutputChars}, case $index: ${ToolValues.describe(items)}"
                val (images, text) = admitted.split('\n').partition(urls::contains)
                assertTrue(images == urls) {
                    "$what: admission kept ${images.size} of ${urls.size} images whole, in order"
                }
                val alone = value.split('\n').filterNot(urls::contains).joinToString("\n")
                assertEquals(CodeModeLimits.boundedText(alone, bounds.maxOutputChars), text.joinToString("\n"), what)
            }
        }
    }
}

/** Content items as CodeModeClientToolValues projects a Claude Code tool result: text, and images as data URLs. */
private object ToolValues {
    private val mimes = listOf("image/png", "image/jpeg", "image/gif", "image/webp")
    private const val LETTERS = "abcdefghijklmnopqrstuvwxyz "

    fun items(random: Random): List<JsonObject> = List(1 + random.nextInt(5)) {
        if (random.nextInt(5) < 2) image(random) else text(random)
    }

    fun url(item: JsonObject): String? = (item["image_url"] as? JsonPrimitive)?.content

    fun describe(items: List<JsonObject>): String = items.joinToString(" + ") { item ->
        url(item)?.let { "image of ${it.length} chars" } ?: "text of ${item["text"]?.jsonPrimitive?.content?.length}"
    }

    private fun image(random: Random): JsonObject = buildJsonObject {
        val data = Base64.getEncoder().encodeToString(random.nextBytes(1 + random.nextInt(MAX_IMAGE_BYTES)))
        put("type", "input_image")
        put("image_url", "data:${mimes.random(random)};base64,$data")
    }

    private fun text(random: Random): JsonObject = buildJsonObject {
        val length = random.nextInt(if (random.nextBoolean()) LETTERS.length else MAX_TEXT)
        put("type", "input_text")
        put(
            "text",
            buildString {
                repeat(length) {
                    append(if (random.nextInt(NEWLINE_EVERY) == 0) '\n' else LETTERS[random.nextInt(LETTERS.length)])
                }
            },
        )
    }
}
