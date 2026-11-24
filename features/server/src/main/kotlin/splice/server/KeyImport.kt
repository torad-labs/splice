package splice.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.topology.PersonEmails
import splice.core.topology.ServerKeyFormat
import java.time.Instant
import java.time.format.DateTimeParseException

/** Authority to admit one existing Splice key, without revealing any part beyond its public prefix. */
internal class KeyImport(
    private val person: String,
    private val prefix: String,
    private val hash: String,
    private val expiresAt: Long?,
    private val revokedAt: Long?,
) {
    fun row(now: Long): ServerKey = ServerKey(
        prefix = prefix,
        hash = hash,
        person = person,
        source = "import",
        createdAt = now,
        expiresAt = expiresAt,
        revokedAt = revokedAt,
    )
}

internal object KeyImportReader {
    private val digest = Regex("[a-fA-F0-9]{64}")

    fun read(body: JsonObject?): KeyImport? {
        val wire = body?.let(::decode) ?: return null
        val person = PersonEmails.read(wire.person) ?: return null
        return if (ServerKeyFormat.validPrefix(wire.prefix) && digest.matches(wire.hash)) {
            imported(wire, person)
        } else {
            null
        }
    }

    private fun decode(body: JsonObject): Wire? = try {
        Json.decodeFromJsonElement(Wire.serializer(), body)
    } catch (_: SerializationException) {
        null
    }

    private fun imported(wire: Wire, person: String): KeyImport? = try {
        KeyImport(person, wire.prefix, wire.hash.lowercase(), epoch(wire.expiresAt), epoch(wire.revokedAt))
    } catch (_: DateTimeParseException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    private fun epoch(time: String?): Long? = time?.let { Instant.parse(it).toEpochMilli() }

    @Serializable
    private data class Wire(
        val person: String,
        val prefix: String,
        val hash: String,
        @SerialName("expires_at") val expiresAt: String? = null,
        @SerialName("revoked_at") val revokedAt: String? = null,
    ) {
        override fun toString(): String = "KeyImportWire(<redacted>)"
    }
}
