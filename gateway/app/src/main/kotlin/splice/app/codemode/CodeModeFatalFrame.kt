// NEW: strict bounded fatal-worker diagnostics stay distinct from guest completion frames.
package splice.app.codemode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.spi.CodeModeInfrastructureCategory
import splice.spi.CodeModeInfrastructureClass
import splice.spi.CodeModeInfrastructureException
import java.io.IOException

internal const val CODE_MODE_FATAL_FRAME_TYPE: String = "fatal"

internal object CodeModeFatalFrame {
    private const val CATEGORY: String = "category"
    private const val FAULT_CLASS: String = "faultClass"
    private const val TYPE: String = "type"

    fun create(
        category: CodeModeInfrastructureCategory,
        faultClass: CodeModeInfrastructureClass,
    ): JsonObject = buildJsonObject {
        put(TYPE, CODE_MODE_FATAL_FRAME_TYPE)
        put(CATEGORY, category.name)
        put(FAULT_CLASS, faultClass.name)
    }

    fun parse(frame: JsonObject): Nothing {
        if (frame.keys != setOf(TYPE, CATEGORY, FAULT_CLASS)) invalid()
        val category = CodeModeInfrastructureCategory.entries.firstOrNull { candidate ->
            candidate.name == string(frame, CATEGORY)
        } ?: invalid()
        val faultClass = CodeModeInfrastructureClass.entries.firstOrNull { candidate ->
            candidate.name == string(frame, FAULT_CLASS)
        } ?: invalid()
        throw CodeModeInfrastructureException(category, faultClass)
    }

    private fun string(frame: JsonObject, name: String): String =
        (frame[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: invalid()

    private fun invalid(): Nothing = throw IOException("Code-mode worker sent invalid fatal diagnostics")
}
