// PORT-OF: codex-rs/core/src/tools/catalog_parameters.rs @ rust-v0.164.0.
package splice.provider.codex.catalog

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.upstream.codemode.CodeModeSchemaProjection
import splice.upstream.codemode.json.CodeModeJson
import splice.upstream.codemode.json.CodeModeJsonException

/** Invalid catalog overrides select bundled parameters with the same reason as Codex's parser. */
internal object CodeModeCatalogParameters {
    data class Parsed(val schema: JsonObject?, val reason: String? = null)

    fun parse(text: String): Parsed {
        val value = try { CodeModeJson.parse(text) } catch (_: CodeModeJsonException) {
            return Parsed(null, "schema is not valid JSON")
        }
        val objectSchema = value as? JsonObject
        if (objectSchema?.get(
                "type",
            ) != JsonPrimitive("object")
        ) {
            return Parsed(null, "schema must declare an object type")
        }
        return CodeModeSchemaProjection.canonical(objectSchema)?.let(::Parsed)
            ?: Parsed(null, "schema uses unsupported JSON Schema structures")
    }
}
