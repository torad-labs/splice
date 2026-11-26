package splice.launch.recipe

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.launch.LaunchModSettings
import splice.launch.ModsOff
import java.nio.charset.CharacterCodingException

internal data class ValidatedLaunchLabels(val settings: LaunchModSettings, val warnings: List<String>)

/** The Mods v2 label contract: validate once before a label can enter daemon state or reach a reader. */
internal object LaunchLabels {
    private val keyShape = Regex("[A-Z][A-Z0-9_]{0,31}")
    private val readableName = Regex("[A-Za-z0-9_]*")

    fun jsonLabels(body: JsonObject?): List<String> = (body?.get("labels") as? JsonObject).orEmpty()
        .mapNotNull { (name, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.let { "$name=${it.content}" }
        }

    fun read(input: LaunchCallerAmbient): ValidatedLaunchLabels {
        val labels = mutableMapOf<String, String>()
        val warnings = input.labels.mapNotNull { offer(it, labels) }
        return ValidatedLaunchLabels(LaunchModSettings(labels.toMap(), modsOff(input.modsOff)), warnings)
    }

    private fun offer(field: String, labels: MutableMap<String, String>): String? {
        val key = field.substringBefore('=')
        val value = field.substringAfter('=', "")
        val reason = rejection(key, field, value, labels)
        if (reason == null) {
            labels[key.lowercase()] = value
            return null
        }
        val name = key.takeIf(readableName::matches)?.let { "SPLICE_LABEL_$it" } ?: "malformed SPLICE_LABEL_ name"
        return "ignoring $name: $reason"
    }

    private fun rejection(key: String, field: String, value: String, labels: Map<String, String>): String? = when {
        !keyShape.matches(key) || '=' !in field -> "expected an uppercase key starting with a letter, 1–32 characters"
        !validValue(value) -> "expected at most $MAX_LABEL_BYTES UTF-8 bytes and no control characters"
        key.lowercase() in labels -> "duplicate key"
        labels.size == MAX_LABELS -> "only $MAX_LABELS labels fit on a session"
        else -> null
    }

    private fun validValue(value: String): Boolean = try {
        value.length <= MAX_LABEL_BYTES && !value.any(Char::isISOControl) &&
            value.encodeToByteArray(throwOnInvalidSequence = true).size <= MAX_LABEL_BYTES
    } catch (_: CharacterCodingException) {
        false
    }

    private fun modsOff(raw: String?): ModsOff =
        if (raw?.trim() == "1") {
            ModsOff(all = true)
        } else {
            ModsOff(names = raw.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).toSet())
        }

    private const val MAX_LABEL_BYTES = 256
    private const val MAX_LABELS = 16
}
