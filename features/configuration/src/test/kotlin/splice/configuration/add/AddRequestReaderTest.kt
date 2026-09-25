// NEW: V4-220 item 3 — POST /api/add's body read into the CLI's own arguments. The CLI's `--model` spec
// reads a trailing `:<digits>` as the window, so a console row that names no window must not reach it
// as a bare id: an ollama tag (`llama3:8`) would be split into id `llama3` with an 8-token window.
package splice.configuration.add

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AddRequestReaderTest {

    private val reader = AddRequestReader()
    private val rows = AddModelRows({ }, AddPrompter { _, default -> default })

    private fun models(body: String): List<AddModel> {
        val request = reader.parse(Json.parseToJsonElement(body).jsonObject)
        val args = (request as AddRequest.Args).args
        return rows.resolve(args, AddProfiles().find("api-key")!!)
    }

    /** RED before the fix: [AddModel(id=llama3, label=llama3, contextWindow=8)]. */
    @Test
    fun `a row with no window keeps a tagged id whole and takes the default window`() {
        val parsed = models("""{"profile":"api-key","models":[{"id":"llama3:8"}]}""")
        assertEquals(listOf(AddModel("llama3:8", "llama3:8", 128_000L)), parsed)
    }

    @Test
    fun `a row with a window keeps a tagged id whole`() {
        val parsed = models("""{"profile":"api-key","models":[{"id":"qwen3:4b","context_window":32768}]}""")
        assertEquals(listOf(AddModel("qwen3:4b", "qwen3:4b", 32_768L)), parsed)
    }

    @Test
    fun `a window that is not a whole number refuses the body`() {
        val body = """{"profile":"api-key","models":[{"id":"m","context_window":"32k"}]}"""
        val request = reader.parse(Json.parseToJsonElement(body).jsonObject)
        assertEquals(AddRequest.Invalid("Each model needs an id, and a context_window in whole tokens."), request)
    }
}
