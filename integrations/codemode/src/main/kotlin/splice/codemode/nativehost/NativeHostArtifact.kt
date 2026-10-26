package splice.codemode.nativehost

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.zip.GZIPInputStream

internal const val NATIVE_HOST_RELEASE: String = "0.164.0"
internal const val NATIVE_HOST_SOURCE_REVISION: String = "6aaac694803004a63c6f393d153c96d22521a6b9"

/** A verified bundled host. It never searches PATH, a Codex installation, or a user-controlled cache. */
public class NativeHostArtifact : AutoCloseable {
    private var directory: Path? = null
    private var executable: Path? = null

    @Synchronized
    public fun resolve(): Path {
        executable?.let { return it }
        val platform = platform(System.getProperty("os.name"), System.getProperty("os.arch"))
        val root = "/splice/codemode/native/$platform/"
        val properties = Properties()
        val manifest = javaClass.getResourceAsStream(root + "host.properties")
            ?: throw IOException("This splice artifact has no pinned native code-mode host for $platform")
        manifest.use(properties::load)
        verifyManifest(properties)
        val expected = properties.getProperty("sha256")
            ?: throw IOException("Bundled native host has no SHA-256 identity")
        val temporary = createDirectory()
        directory = temporary
        val name = if (platform.startsWith("windows")) "codex-code-mode-host.exe" else "codex-code-mode-host"
        val target = temporary.resolve(name)
        unpack(root, target, expected)
        executable = target
        return target
    }

    private fun verifyManifest(properties: Properties) {
        val expected = mapOf(
            "protocolVersion" to "1",
            "release" to NATIVE_HOST_RELEASE,
            "sourceRevision" to NATIVE_HOST_SOURCE_REVISION,
        )
        if (expected.any { (name, value) -> properties.getProperty(name) != value }) {
            throw IOException("Bundled native host does not match the pinned Codex release and source")
        }
    }

    private fun unpack(root: String, target: Path, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        val resource = javaClass.getResourceAsStream(root + "codex-code-mode-host.gz")
            ?: throw IOException("Bundled native host payload is missing")
        GZIPInputStream(resource).use { input ->
            java.security.DigestInputStream(input, digest).use { verified ->
                Files.newOutputStream(target).use(verified::transferTo)
            }
        }
        val actual = java.util.HexFormat.of().formatHex(digest.digest())
        verifyExecutable(target, actual, expected)
    }

    private fun verifyExecutable(target: Path, actual: String, expected: String) {
        if (actual != expected) throw IOException("Bundled native host failed SHA-256 verification")
        if (!target.toFile().setExecutable(true, true)) throw IOException("Cannot make bundled native host executable")
    }

    private fun createDirectory(): Path = if (
        Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir"))).supportsFileAttributeView("posix")
    ) {
        Files.createTempDirectory(
            "splice-native-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
    } else {
        Files.createTempDirectory("splice-native-")
    }

    private fun platform(os: String, arch: String): String {
        val system = os.lowercase(Locale.ROOT)
        val processor = arch.lowercase(Locale.ROOT)
        val architecture = when (processor) {
            "amd64", "x86_64" -> "amd64"
            "aarch64", "arm64" -> "aarch64"
            else -> throw IOException("Unsupported native code-mode architecture: $arch")
        }
        val platform = when {
            system.startsWith("windows") -> "windows-$architecture"
            system.startsWith("mac") -> "darwin-$architecture"
            system.startsWith("linux") -> "linux-$architecture"
            else -> throw IOException("Unsupported native code-mode system: $os")
        }
        return platform
    }

    @Synchronized
    override fun close() {
        executable?.let(Files::deleteIfExists)
        executable = null
        directory?.let { path ->
            Files.newDirectoryStream(path).use { children -> children.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(path)
        }
        directory = null
    }
}
