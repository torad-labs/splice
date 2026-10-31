package splice.codemode.nativehost

import splice.upstream.codemode.availability.CodeModeAvailability
import java.io.IOException
import java.net.URL
import java.util.Locale
import java.util.Properties

// why: pinned manifests are tiny; 4 KiB bounds preflight resource reads before any host payload is opened.
private const val MANIFEST_BYTES: Int = 4096

/** Bounded resource preflight. Payload verification and process startup remain owned by resolve/open. */
internal class NativeHostReadiness(
    resources: ClassLoader? = Thread.currentThread().contextClassLoader,
    private val os: String = System.getProperty("os.name"),
    private val arch: String = System.getProperty("os.arch"),
) {
    private val loader = resources ?: ClassLoader.getSystemClassLoader()
    private val pinned: NativeHostBundle by lazy { read() }

    fun availability(): CodeModeAvailability = try {
        bundle()
        CodeModeAvailability.Available
    } catch (error: IOException) {
        CodeModeAvailability.Unavailable(checkNotNull(error.message))
    }

    fun bundle(): NativeHostBundle = pinned

    private fun read(): NativeHostBundle {
        val platform = platform()
        val root = "splice/codemode/native/$platform/"
        val properties = manifest(root, platform)
        verify(properties)
        val sha256 = properties.getProperty("sha256")
        if (sha256 == null || !sha256.matches(Regex("[a-f0-9]{64}"))) {
            throw IOException("Bundled native host has no valid SHA-256 identity")
        }
        val payload = loader.getResource(root + "codex-code-mode-host.gz")
            ?: throw IOException("Bundled native host payload is missing")
        return NativeHostBundle(platform, sha256, payload)
    }

    private fun manifest(root: String, platform: String): Properties {
        val bytes = loader.getResourceAsStream(root + "host.properties")?.use { it.readNBytes(MANIFEST_BYTES + 1) }
            ?: throw IOException("This splice artifact has no pinned native code-mode host for $platform")
        if (bytes.size > MANIFEST_BYTES) throw IOException("Bundled native host manifest is too large")
        return Properties().apply { bytes.inputStream().use(::load) }
    }

    private fun verify(properties: Properties) {
        val expected = mapOf(
            "protocolVersion" to "1",
            "release" to NATIVE_HOST_RELEASE,
            "sourceRevision" to NATIVE_HOST_SOURCE_REVISION,
        )
        if (expected.any { (name, value) -> properties.getProperty(name) != value }) {
            throw IOException("Bundled native host does not match the pinned Codex release and source")
        }
    }

    private fun platform(): String {
        val architecture = when (arch.lowercase(Locale.ROOT)) {
            "amd64", "x86_64" -> "amd64"
            "aarch64", "arm64" -> "aarch64"
            else -> throw IOException("Unsupported native code-mode architecture: $arch")
        }
        val system = os.lowercase(Locale.ROOT)
        return when {
            system.startsWith("windows") -> "windows-$architecture"
            system.startsWith("mac") -> "darwin-$architecture"
            system.startsWith("linux") -> "linux-$architecture"
            else -> throw IOException("Unsupported native code-mode system: $os")
        }
    }
}

internal data class NativeHostBundle(val platform: String, val sha256: String, val payload: URL)
