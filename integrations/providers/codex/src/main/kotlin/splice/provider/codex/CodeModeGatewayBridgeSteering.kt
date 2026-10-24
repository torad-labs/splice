package splice.provider.codex

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal class CodeModeGatewayBridgeSteering(private val fixture: CodeModeGatewayProbeSupport) {
    suspend fun run(): JsonObject {
        val observed = mutableMapOf<String, JsonElement>()
        for (kind in listOf("newUser", "editedOldResult", "rewind", "compaction")) {
            val directory = fixture.location.dir.parent.resolve(kind)
            val scoped = CodeModeGatewayProbeSupport(fixture.nonce, directory)
            observed[kind] = CodeModeGatewayBridgeProbe(scoped).steering(kind)
        }
        return JsonObject(observed)
    }
}
