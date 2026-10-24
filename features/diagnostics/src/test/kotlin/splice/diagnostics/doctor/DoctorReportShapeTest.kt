// `splice doctor --json` is what someone shares to show what their heads run: the report names the splice
// version it came from, each provider's dialect, and every model's id and context window.
package splice.diagnostics.doctor

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DoctorReportShapeTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `the report carries the splice version, each provider's dialect and each model's id and window`() {
        val fixture = DoctorReportFixture(tmp)

        val out = fixture.report(fixture.plant(), withLogs = false)

        assertEquals(
            splice.core.GATEWAY_VERSION,
            out.getValue("splice").jsonObject.getValue("version").jsonPrimitive.content,
            "the report names the splice version it came from",
        )
        val provider = out.getValue("topology").jsonObject.getValue("providers").jsonObject.getValue("codex").jsonObject
        assertEquals("openai_responses", provider.getValue("dialect").jsonPrimitive.content, "the dialect is reported")
        val model = provider.getValue("models").jsonArray.single().jsonObject
        assertEquals("gpt-6-astra", model.getValue("id").jsonPrimitive.content)
        assertEquals(272_000L, model.getValue("context_window").jsonPrimitive.content.toLong())
    }
}
