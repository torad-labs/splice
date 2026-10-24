package splice.provider.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.provider.codex.history.CodeModeOwnership
import splice.provider.codex.state.CodeModeExtraContent

internal class CodeModeGatewayProbeReplay(private val fixture: CodeModeGatewayProbeSupport) {
    private val extras = CodeModeExtraContent(fixture.codec, CodeModeOwnership(fixture.codec))
    private val callbacks = fixture.callback("one") + fixture.callback("two")

    fun replay(): JsonObject {
        val original = fixture.record()
        val encoded = fixture.json.encodeToString(CodeModeRecordSnapshot.serializer(), original.snapshot())
        val restored = fixture.json.decodeFromString<CodeModeRecordSnapshot>(encoded).restore()
        val client = JsonArray(fixture.baseline + callbacks)
        return buildJsonObject {
            put("baseline", JsonArray(fixture.baseline)); put("client", client)
            put("beforeSaveClassification", extras.of(client, original, emptyMap()).name)
            put("afterRestoreClassification", extras.of(client, restored, emptyMap()).name)
            put("snapshot", fixture.json.parseToJsonElement(encoded))
            put("diagnostic", extras.describe(client, restored, emptyMap(), extras.of(client, restored, emptyMap())))
        }
    }

    fun steering(): JsonObject {
        val newUser = fixture.baseline + callbacks + fixture.user("steer-${fixture.nonce}")
        val edited = replaced("edited-${fixture.nonce}") + callbacks
        val unknown = fixture.baseline + buildJsonObject {
            put("type", "reasoning"); put("id", "rs-unknown-${fixture.nonce}")
            put("summary", JsonArray(emptyList())); put("encrypted_content", "new-${fixture.nonce}")
        } + callbacks
        return buildJsonObject {
            put("newUser", classified(newUser))
            put("editedOldResult", classified(edited))
            put("unknownReasoning", classified(unknown))
            put("historyBranches", CodeModeGatewayProbeHistory(fixture).branches())
        }
    }

    fun cleared(): JsonObject {
        val record = fixture.record()
        val cleared = replaced("[Old tool result content cleared]") + callbacks
        val client = JsonArray(cleared)
        return buildJsonObject {
            put("baseline", JsonArray(fixture.baseline)); put("client", client)
            put("withoutWitness", extras.of(client, record, emptyMap()).name)
            put("withAuthorizedWitness", extras.of(client, record, emptyMap(), clearedCallbacks = setOf("old")).name)
            put(
                "scope",
                "supplied authorization classification; session witness and canonical chain need integration evidence",
            )
        }
    }

    private fun classified(items: List<JsonElement>): JsonObject = buildJsonObject {
        put("client", JsonArray(items))
        put("classification", extras.of(JsonArray(items), fixture.record(), emptyMap()).name)
    }

    private fun replaced(output: String): List<JsonElement> = fixture.baseline.map { value ->
        val item = value as? JsonObject
        if (value == fixture.callback("old")[1] && item != null) {
            JsonObject(item + ("output" to JsonPrimitive(output)))
        } else {
            value
        }
    }
}
