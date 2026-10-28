package splice.codemode.nativehost

import splice.upstream.codemode.availability.CodeModeAvailability
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

internal const val NATIVE_HOST_RELEASE: String = "0.165.0"
internal const val NATIVE_HOST_SOURCE_REVISION: String = "360319d8dee62d87e2d59fc0c21805d79519cfed"

/** A verified bundled host. It never searches PATH, a Codex installation, or a user-controlled cache. */
public class NativeHostArtifact : AutoCloseable {
    private val readiness = NativeHostReadiness()
    private var directory: Path? = null
    private var executable: Path? = null

    /** Checks only the pinned platform manifest and payload presence, without unpacking or spawning a host. */
    public fun availability(): CodeModeAvailability = readiness.availability()

    @Synchronized
    public fun resolve(): Path {
        executable?.let { return it }
        val bundle = readiness.bundle()
        val temporary = createDirectory()
        directory = temporary
        val name = if (bundle.platform.startsWith("windows")) "codex-code-mode-host.exe" else "codex-code-mode-host"
        val target = temporary.resolve(name)
        unpack(bundle.payload, target, bundle.sha256)
        executable = target
        return target
    }

    private fun unpack(source: URL, target: Path, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        val resource = source.openStream()
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
