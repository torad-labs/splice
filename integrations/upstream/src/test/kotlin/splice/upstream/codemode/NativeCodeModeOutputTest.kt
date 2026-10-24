package splice.upstream.codemode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.util.JsonScalars
import java.util.Base64

class NativeCodeModeOutputTest {
    @Test
    fun `pure text gets native formatted middle truncation after its status header`() {
        val output = NativeCodeModeOutput.content(CodeModeStep.Completed("0123456789abcdefghij"), 1_550, 2)
        assertEquals("Script completed\nWall time 1.6 seconds\nOutput:\n", JsonScalars.str(output[0], "text"))
        assertEquals(
            "Warning: truncated output (original token count: 5)\nTotal output lines: 1\n\n" +
                "0123…3 tokens truncated…ghij",
            JsonScalars.str(output[1], "text"),
        )
    }

    @Test
    fun `UTF8 middle truncation keeps complete code points and zero budget retains the native marker`() {
        assertEquals("α…3 tokens truncated…θ", NativeCodeModeTruncation.text("αβγδεζηθ", 1))
        assertEquals("…2 tokens truncated…", NativeCodeModeTruncation.text("😀😀😀", 1))
        assertEquals("…3 tokens truncated…", NativeCodeModeTruncation.text("😀😀😀", 0))
    }

    @Test
    fun `mixed output keeps images and order while counting omitted audio and text`() {
        val image = media("input_image", "image_url", "data:image/png;base64,YQ==")
        val audio = media("input_audio", "audio_url", "data:audio/wav;base64,AAAA")
        val content = listOf(
            NativeCodeModeOutput.textItem("abcdefghijk"),
            image,
            audio,
            NativeCodeModeOutput.textItem("tail"),
        )
        val output = NativeCodeModeTruncation.content(content, 2)
        assertEquals("abcd…1 tokens truncated…hijk", JsonScalars.str(output[0], "text"))
        assertEquals(image, output[1])
        assertEquals("[omitted 1 text items ...]", JsonScalars.str(output[2], "text"))
        assertEquals("[omitted 1 audio items ...]", JsonScalars.str(output[3], "text"))
    }

    @Test
    fun `native failure text and original image support retain their meaning`() {
        val image = buildJsonObject {
            put("type", "input_image")
            put("image_url", "data:image/png;base64,YQ==")
            put("detail", "original")
        }
        val step = CodeModeStep.Completed("", "broken", listOf(image))
        val output = NativeCodeModeOutput.content(step, 0, supportsOriginal = false)
        assertEquals("Script failed\nWall time 0.0 seconds\nOutput:\n", JsonScalars.str(output[0], "text"))
        assertEquals("high", JsonScalars.str(output[1], "detail"))
        assertEquals("Script error:\nbroken", JsonScalars.str(output[2], "text"))
        assertEquals("original", JsonScalars.str(image, "detail"))
    }

    @Test
    fun `Opus duration keeps native codec delay at the model output budget boundary`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("native-audio-1s-opus.ogg")).use { it.readAllBytes() }
        val url = "data:audio/ogg;base64," + Base64.getEncoder().encodeToString(bytes)
        val audio = media("input_audio", "audio_url", url)
        // Untouched upstream codex-utils-audio + Symphonia 0.6 returns 11 for these exact bytes.
        assertEquals(11L, NativeCodeModeAudio.tokens(url))
        assertEquals(listOf(audio), NativeCodeModeTruncation.content(listOf(audio), 11))
        val omitted = NativeCodeModeTruncation.content(listOf(audio), 10).single()
        assertEquals("[omitted 1 audio items ...]", JsonScalars.str(omitted, "text"))
    }

    private fun media(type: String, field: String, value: String): JsonObject = buildJsonObject {
        put("type", type)
        put(field, value)
    }
}
