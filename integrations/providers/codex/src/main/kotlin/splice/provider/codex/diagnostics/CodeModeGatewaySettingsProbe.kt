package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeConfiguration
import splice.upstream.RoundResult
import splice.upstream.codemode.NativeCodeModeLifecycle

/** Exercises the same decoded head settings and turn builder used by the Responses provider. */
internal class CodeModeGatewaySettingsProbe(
    private val fixture: CodeModeGatewayProbeSupport,
    private val runtime: NativeCodeModeLifecycle,
) {
    suspend fun run(): JsonObject {
        val manager = CodexCodeModeBridge(CodeModeBridgeConfig({ runtime }, fixture.location, log = fixture.log))
        try {
            return buildJsonObject {
                put("schema", 1); put("caseId", "runtime.head-settings"); put("nonce", fixture.nonce)
                put(
                    "pid",
                    ProcessHandle.current().pid(),
                ); put("scope", "real-native-runtime-through-production-gateway")
                put("default", execute(manager, "default", JsonObject(emptyMap())))
                put(
                    "configured",
                    execute(
                        manager,
                        "configured",
                        buildJsonObject {
                            put("codeModeDefaultExecYieldTimeMs", 1)
                            put("codeModeToolDescriptionFirst", true); put("codeModeShowCellOverhead", true)
                            put("codeModeToolInputSchemaMaxBytes", CUSTOM_SCHEMA_BUDGET)
                        },
                    ),
                )
                put("catalog", execute(manager, "catalog", buildJsonObject { put("codeModeUnifiedImageBudget", true) }))
            }
        } finally {
            manager.onProviderStop()
        }
    }

    private fun settings(label: String, config: JsonObject) = run {
        val overrides = config.mapValues { (_, value) -> checkNotNull(JsonScalars.str(value)) }
        val service =
            ConfigService(
                StatePaths(
                    baseOverride = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
                        .resolve("splice-code-mode-settings-${fixture.nonce}"),
                ),
                perHeadOverrides = mapOf("probe" to overrides),
                envReader = EnvReader { null },
                log = fixture.log,
            )
        val configured = CodexCodeModeConfiguration.read(service.getConfig("probe"))
        if (label == "catalog") {
            configured.copy(
                modelCatalog = CodeModeGatewayCatalogMessages,
            )
        } else {
            configured
        }
    }

    private suspend fun execute(manager: CodexCodeModeBridge, label: String, config: JsonObject): JsonObject {
        val request = CodeModeGatewaySearchRequests(
            fixture,
            settings(label, config),
            "Namespace guidance.",
        ).prepare(manager, false, emptyList())
        val posted = ArrayList<JsonArray>()
        val callId = "settings-$label-${fixture.nonce}"
        var started = false
        var waited = false
        val result = checkNotNull(
            request.roundInterceptor,
        ).intercept(JsonWire.string(request.requestBody), CodeModeGatewayBridgeSink()) { body ->
            val input = fixture.json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
            posted += input
            val cell = CodeModeGatewayNativeResults.runningCell(input, callId)
            val call = when {
                !started -> {
                    started = true
                    CodeModeGatewayCalls.exec(
                        callId,
                        "text(ALL_TOOLS.find(t=>t.name==='Ping').description); " +
                            "await new Promise(r=>setTimeout(r,150)); text('settings-$label-completed');",
                    )
                }
                !waited && cell != null -> {
                    waited = true
                    CodeModeGatewayCalls.wait("settings-wait-$label-${fixture.nonce}", cell)
                }
                else -> null
            }
            RoundResult.Outcome(
                TurnOutcome.Success(
                    false,
                    false,
                    noRequestUsage,
                    handoffs = RoundHandoffs(customCalls = listOfNotNull(call)),
                ),
            )
        }
        return summary(config, request.requestBody, posted, waited, result)
    }

    private fun summary(
        config: JsonObject,
        declaration: JsonObject,
        posted: List<JsonArray>,
        waited: Boolean,
        result: RoundResult,
    ): JsonObject = buildJsonObject {
        put(
            "config",
            config,
        ); put("declaration", declaration); put("posted", JsonArray(posted)); put("waited", waited)
        put(
            "outcome",
            when (result) {
                is RoundResult.Ended -> "Ended"
                is RoundResult.Outcome -> (result.outcome as? TurnOutcome.Failure)?.evidence?.message
                    ?: result.outcome::class.simpleName.orEmpty()
            },
        )
    }
}

// why: the configured case's codeModeToolInputSchemaMaxBytes, other than the 16_000 default (Codex rust-v0.164.0
// code-mode-protocol/src/json_schema_types.rs:13); the port chose the number.
private const val CUSTOM_SCHEMA_BUDGET = 18_000
