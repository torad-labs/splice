package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.provider.codex.catalog.CodeModeCatalogMessages
import splice.provider.codex.catalog.CodeModeWaitTool

/** Catalog overrides use the same wait factory as the production declaration. */
public object CodeModeCatalogProbe {
    public fun evaluate(request: JsonObject): JsonObject = buildJsonObject {
        put(
            "declaration",
            CodeModeWaitTool.declaration(CodeModeCatalogMessages(request["model_messages"] as? JsonObject)),
        )
    }
}
