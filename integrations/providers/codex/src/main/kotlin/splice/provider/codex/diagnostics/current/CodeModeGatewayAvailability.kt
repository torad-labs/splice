package splice.provider.codex.diagnostics.current

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.provider.codex.CodeModeRuntimes
import splice.provider.codex.CodeModeToolPolicy
import splice.provider.codex.catalog.CodeModePresentation
import splice.provider.codex.diagnostics.CodeModeGatewayProbeSupport
import splice.upstream.codemode.availability.CODE_MODE_HOST_DISABLED
import splice.upstream.codemode.availability.CodeModeAvailability
import splice.upstream.codemode.availability.CodeModeToolMode

/** Explicit negative boundary: the application's production factory must independently report unavailable. */
internal class CodeModeGatewayAvailability(
    private val fixture: CodeModeGatewayProbeSupport,
    specification: JsonObject,
) {
    private val input = specification.getValue("availability").jsonObject
    private val requested = checkNotNull(JsonScalars.str(input, "toolMode"))
    private val mode = when (requested) {
        "direct" -> CodeModeToolMode.DIRECT
        "code_mode" -> CodeModeToolMode.CODE_MODE
        "code_mode_only" -> CodeModeToolMode.CODE_MODE_ONLY
        else -> error("Unknown requested source tool mode")
    }
    val policy = CodeModeToolPolicy(presentation = CodeModePresentation(toolMode = mode))

    init {
        require(input.keys == setOf("host", "toolMode") && JsonScalars.str(input, "host") == "disabled")
        require(specification["callbacks"] == JsonArray(emptyList()))
        require(listOf("interruption", "boundary", "rankedSearch").none(specification::containsKey))
    }

    fun runtimes(supplied: CodeModeRuntimes?): CodeModeRuntimes {
        val actual = checkNotNull(supplied) { "The application must supply its actual disabled-provider factory" }
        check(actual.availability() == CodeModeAvailability.Unavailable(CODE_MODE_HOST_DISABLED)) {
            "Application preflight differs from the explicit source availability case"
        }
        return actual
    }

    fun report(report: JsonObject): JsonObject = JsonObject(
        report + mapOf(
            "scope" to JsonPrimitive("production-gateway-unavailable-provider"),
            "availability" to buildJsonObject {
                put("host", "disabled"); put("toolMode", requested)
                put("reason", CODE_MODE_HOST_DISABLED)
                put("noticeChannel", "head-log")
                put(
                    "notices",
                    JsonArray(
                        fixture.logs.filter { it.contains("Code Mode is unavailable because") }.map(::JsonPrimitive),
                    ),
                )
            },
        ),
    )
}
