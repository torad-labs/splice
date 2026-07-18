// NEW: Kimi (Moonshot) device identity — the X-Msh-* headers sent on OAuth calls AND upstream
// turns, plus a stable device_id (uuid) persisted next to the auth file (0600). Every header value
// is ASCII-sanitized: non-ASCII chars are stripped and an empty result becomes "unknown", because
// Ktor throws on non-Latin1 header values and CJK hostnames exist in the wild. The header set is
// exactly the five X-Msh-* names the wire contract enumerates; device_id is persisted for identity
// continuity and exposed via deviceId() for the login/wire layers.
package splice.provider.kimi

import splice.core.GATEWAY_VERSION
import splice.core.util.runCatchingCancellable
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

public class KimiDeviceIdentity(
    private val deviceIdPath: Path,
    private val version: String = GATEWAY_VERSION,
    private val rawHostname: String = defaultHostname(),
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val osVersion: String = System.getProperty("os.version").orEmpty(),
    private val osArch: String = System.getProperty("os.arch").orEmpty(),
) {

    /** Read-or-create the persisted device_id (uuid, 0600). */
    public fun deviceId(): String {
        val existing = runCatchingCancellable {
            if (Files.exists(deviceIdPath)) Files.readString(deviceIdPath).trim() else null
        }.getOrNull()
        if (!existing.isNullOrEmpty()) return existing
        val id = UUID.randomUUID().toString()
        writeSecure(deviceIdPath, id)
        return id
    }

    /** The X-Msh-* identity headers; every value ASCII-sanitized. */
    public fun headers(): Map<String, String> = mapOf(
        "X-Msh-Platform" to asciiSanitize("splice"),
        "X-Msh-Version" to asciiSanitize(version),
        "X-Msh-Device-Name" to asciiSanitize(rawHostname),
        "X-Msh-Device-Model" to asciiSanitize("$osName $osVersion $osArch"),
        "X-Msh-Os-Version" to asciiSanitize(osVersion),
    )

    private companion object {
        const val ASCII_CEILING = 0x80

        fun asciiSanitize(value: String): String =
            value.filter { it.code < ASCII_CEILING }.ifEmpty { "unknown" }

        fun defaultHostname(): String =
            runCatchingCancellable { InetAddress.getLocalHost().hostName }.getOrNull() ?: "unknown"
    }
}
