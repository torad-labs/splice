// NEW: streamed prefix hashing preserves legacy UTF-8 bytes without retaining prompt-sized encodings.
package splice.core.perf

import com.sun.management.ThreadMXBean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.HexFormat

class InputDigestTest {
    @Test
    fun `capture and string digests preserve legacy bytes and member order`() {
        val literals = listOf("1e2", "1.00", "-0", "184467440737095516160", "1e-300")
        val strings = listOf("", "café 🧪", "\uD800", "\uDC00", "\uD800x\uDC00", "\u0000\n\t\"\\")
        val items = literals.map(Json::parseToJsonElement) + strings.map(::JsonPrimitive)
        for (item in items) {
            val request = request(listOf(item), "instructions")
            val prefix = checkNotNull(InputDigest.capture(request))
            assertEquals(sha(JsonArray(listOf(item)).toString()), prefix.inputDigest)
            assertEquals(sha("""{"instructions":"instructions"}"""), prefix.propertiesDigest)
        }
        strings.forEach { assertEquals(sha(it), InputDigest.hex(it)) }
        val first = Json.parseToJsonElement("""{"a":1,"b":2}""")
        val reordered = Json.parseToJsonElement("""{"b":2,"a":1}""")
        val prefix = checkNotNull(InputDigest.capture(request(listOf(first))))
        assertNull(prefix.textGrowthBytes(request(listOf(reordered))), "JSON member order is persisted wire identity")
    }

    @Test
    fun `growth counts only new canonical UTF8 bytes including the separator`() {
        val text = JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive("café 🧪\uD800")))
        for (items in listOf(emptyList(), listOf(text))) {
            val prefix = checkNotNull(InputDigest.capture(request(items)))
            assertEquals(0L, prefix.textGrowthBytes(request(items)))
            val expected = text.toString().toByteArray(Charsets.UTF_8).size.toLong() + if (items.isEmpty()) 0 else 1
            assertEquals(expected, prefix.textGrowthBytes(request(items + text)))
        }
    }

    @Test
    fun `changed properties old history and encoded media cannot justify text growth`() {
        val old = JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive("old")))
        val changed = JsonObject(old + ("content" to JsonPrimitive("changed")))
        val prefix = checkNotNull(InputDigest.capture(request(listOf(old), "original")))
        assertNull(prefix.textGrowthBytes(request(listOf(changed), "original")))
        assertNull(prefix.textGrowthBytes(request(listOf(old), "changed")))
        val image = Json.parseToJsonElement(
            """{"role":"user","content":[{"type":"input_image","image_url":"data:x"}]}""",
        )
        assertNull(prefix.textGrowthBytes(request(listOf(old, image), "original")))
    }

    @Test
    fun `capture and growth allocate less than a prompt sized encoding`() {
        val item = JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive("x".repeat(1_000_000))))
        val initial = request(listOf(item))
        val prefix = checkNotNull(InputDigest.capture(initial))
        val appended = request(listOf(item, item))
        val capture = allocated { checkNotNull(InputDigest.capture(initial)) }
        val growth = allocated { checkNotNull(prefix.textGrowthBytes(appended)) }
        assertTrue(capture < 250_000, "capture allocated $capture bytes for a one-million-byte prompt")
        assertTrue(growth < 250_000, "growth allocated $growth bytes for the old and new prompt items")
    }

    private fun request(items: List<JsonElement>, instructions: String = "") = JsonObject(
        mapOf("input" to JsonArray(items), "instructions" to JsonPrimitive(instructions)),
    )

    private fun sha(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
    )

    private fun allocated(action: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        repeat(8) { action() }
        val thread = Thread.currentThread().threadId()
        val start = bean.getThreadAllocatedBytes(thread)
        repeat(8) { action() }
        return (bean.getThreadAllocatedBytes(thread) - start) / 8
    }
}
