package splice.lifecycle.seatd

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import splice.core.install.seatdPipePinDocument
import splice.lifecycle.release.ReleaseDirectories

/** One checked-in asset manifest, copied unchanged into the jar by processResources. */
@Serializable
internal data class SeatdPin(
    val version: String,
    val urlTemplate: String,
    val platforms: Map<String, SeatdAsset>,
    val repository: String,
) {
    fun reviewed(): Boolean =
        !version.endsWith("-unreleased") && ReleaseDirectories.isVersion(version) &&
            platforms.isNotEmpty() && platforms.values.all(SeatdAsset::reviewed)

    fun download(platform: SeatdPlatform): SeatdDownload? = platform.key()?.let(platforms::get)?.let { asset ->
        val url = urlTemplate.replace("{repository}", repository)
            .replace("{version}", version)
            .replace("{asset}", asset.asset)
        SeatdDownload(url, asset.sha256)
    }
}

@Serializable
internal data class SeatdAsset(val asset: String, val sha256: String) {
    fun reviewed(): Boolean = SEATD_SHA256.matches(sha256) && sha256.any { it != '0' }
}

internal data class SeatdDownload(val url: String, val sha256: String)

private val SEATD_SHA256 = Regex("[a-fA-F0-9]{64}")

internal object SeatdPins {
    val bundled: SeatdPin? by lazy {
        try {
            seatdPipePinDocument?.let { Json.decodeFromJsonElement<SeatdPin>(it) }
        } catch (_: SerializationException) {
            null
        }
    }
}

internal class SeatdPlatform(
    val os: String = System.getProperty("os.name").orEmpty(),
    val arch: String = System.getProperty("os.arch").orEmpty(),
) {
    val linux: Boolean get() = os.startsWith("Linux", ignoreCase = true)
    private val mac: Boolean get() = os.startsWith("Mac", ignoreCase = true)

    fun key(): String? = when (arch.lowercase()) {
        "amd64", "x86_64" -> named("linux-x86_64", "darwin-x86_64")
        "aarch64", "arm64" -> named("linux-aarch64", "darwin-arm64")
        else -> null
    }

    private fun named(linuxKey: String, macKey: String): String? = when {
        linux -> linuxKey
        mac -> macKey
        else -> null
    }
}
