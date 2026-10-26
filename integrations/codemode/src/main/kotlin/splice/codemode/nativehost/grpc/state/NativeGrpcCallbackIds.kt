package splice.codemode.nativehost.grpc.state

import java.util.UUID

internal object NativeGrpcCallbackIds {
    private val hyphenated = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
    private val simple = Regex("([0-9A-Fa-f]{8})([0-9A-Fa-f]{4})([0-9A-Fa-f]{4})([0-9A-Fa-f]{4})([0-9A-Fa-f]{12})")

    fun parse(value: String, field: String): UUID {
        val text = when {
            value.startsWith("urn:uuid:") -> value.removePrefix("urn:uuid:")
            value.startsWith('{') && value.endsWith('}') -> value.substring(1, value.length - 1)
            else -> value
        }
        require(hyphenated.matches(text) || simple.matches(text)) { "code-mode $field ID must be a UUID" }
        val canonical = simple.replace(text, "$1-$2-$3-$4-$5")
        return UUID.fromString(canonical)
    }
}
