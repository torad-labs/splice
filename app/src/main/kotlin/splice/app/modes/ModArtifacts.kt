package splice.app.modes

import splice.core.topology.mods.ModJar
import splice.core.topology.mods.ModsConfig
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE
import java.security.MessageDigest
import java.util.HexFormat

/** All loading uses the byte array this reader hashes, never a second open by the class loader. */
internal class ModArtifacts(private val stateDir: Path) {
    fun acquire(mod: ModJar, directory: ModDirectory): PinnedMod {
        val local = mod.path
        val artifact = if (local == null) {
            urlJar(mod.sha256, checkNotNull(mod.url))
        } else {
            val path = directory.jar(local)
            PinnedMod(path, read(path), false)
        }
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact.bytes))
        if (digest != mod.sha256) throw ModFileFailure("SHA-256 does not match the configured pin", "sha256")
        return artifact
    }

    private fun urlJar(pin: String, url: String): PinnedMod {
        val address = address(url)
        val path = stateDir.resolve("mod-$pin.jar")
        val cached = try {
            read(path)
        } catch (_: NoSuchFileException) {
            null
        }
        return if (cached == null) PinnedMod(path, download(address), true) else PinnedMod(path, cached, false)
    }

    private fun address(raw: String): URI {
        val uri = try {
            URI(raw)
        } catch (_: URISyntaxException) {
            throw ModFileFailure("expected an HTTP or HTTPS URL", "url")
        }
        val web = uri.scheme?.lowercase() in HTTP_SCHEMES && uri.host != null
        if (!web || uri.userInfo != null) {
            throw ModFileFailure("expected an HTTP or HTTPS URL without embedded credentials", "url")
        }
        return uri
    }

    private fun download(address: URI): ByteArray {
        val connection = address.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = MOD_TIMEOUT_MS
            connection.readTimeout = MOD_TIMEOUT_MS
            if (connection.responseCode !in HTTP_OK_START..HTTP_OK_END) {
                throw ModFileFailure("download did not return success; use a direct URL", "url")
            }
            return connection.inputStream.use(::bounded)
        } finally {
            connection.disconnect()
        }
    }

    private fun read(path: Path): ByteArray {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            if (Files.notExists(path, NOFOLLOW_LINKS)) throw NoSuchFileException("")
            throw ModFileFailure("expected a readable regular JAR file")
        }
        return Files.newInputStream(path, NOFOLLOW_LINKS).use(::bounded)
    }

    private fun bounded(source: InputStream): ByteArray {
        val bytes = source.readNBytes(MAX_JAR_BYTES + 1)
        if (bytes.size > MAX_JAR_BYTES) throw ModFileFailure("archive exceeds the 32 MiB size limit")
        return bytes
    }
}

internal data class PinnedMod(val path: Path, val bytes: ByteArray, val downloaded: Boolean) {
    fun cache() {
        SecureFile.writeAtomic0600(path, bytes)
    }
}

/** A local archive must be contained by its directory even after resolving symbolic links. */
internal class ModDirectory(private val config: ModsConfig, private val configDir: Path) {
    private val configured = config.dir != null || config.jars.any { it.path?.let(::isRelative) == true }
    val root: Result<Path?> = Cancellables.runCatchingCancellable { if (configured) root() else null }

    fun jar(raw: String): Path {
        val declared = path(raw)
        val root = root.getOrThrow()
        val requested = (if (declared.isAbsolute) declared else checkNotNull(root).resolve(declared)).normalize()
        val anchor = if (declared.isAbsolute && root?.let(requested::startsWith) != true) {
            requireDirectory(parent(requested)).toRealPath()
        } else {
            checkNotNull(root)
        }
        if (!declared.isAbsolute && !requested.startsWith(anchor)) {
            throw ModFileFailure("relative archive path escapes mods.dir", "path")
        }
        val actual = requested.toRealPath()
        val parent = parent(actual)
        if (!parent.startsWith(anchor)) throw ModFileFailure("archive symlink escapes its directory", "path")
        generateSequence(parent, Path::getParent).takeWhile { it.startsWith(anchor) }.forEach(::requireDirectory)
        return actual
    }

    private fun root(): Path {
        val declared = path(config.dir ?: "mods")
        val requested = configDir.toRealPath().resolve(declared).normalize()
        val resolved = requireDirectory(requested).toRealPath()
        if (!declared.isAbsolute && resolved != requested) {
            throw ModFileFailure("relative mods.dir resolves through a symbolic link")
        }
        return resolved
    }

    private fun isRelative(raw: String): Boolean = try {
        !Path.of(raw).isAbsolute
    } catch (_: InvalidPathException) {
        false
    }

    private fun path(raw: String): Path = try {
        Path.of(raw)
    } catch (_: InvalidPathException) {
        throw ModFileFailure("invalid filesystem path")
    }

    private fun parent(path: Path): Path =
        path.parent ?: throw ModFileFailure("expected a JAR file", "path")

    private fun requireDirectory(path: Path): Path {
        val problem = when {
            Files.isSymbolicLink(path) -> "symbolic link directories are not allowed"
            !Files.isDirectory(path) -> "directory does not exist or is not readable"
            worldWritable(path) -> "directory is world-writable"
            else -> null
        }
        if (problem != null) throw ModFileFailure(problem)
        return path
    }

    private fun worldWritable(path: Path): Boolean = try {
        OTHERS_WRITE in Files.getPosixFilePermissions(path)
    } catch (_: UnsupportedOperationException) {
        throw ModFileFailure("cannot verify directory permissions on this filesystem")
    }
}

/** Safe diagnostic; neither a URL, a local path, nor an exception from mod code is part of a finding. */
internal class ModFileFailure(val reason: String, val field: String = "") : IOException(reason)

// Bound the raw bytes held during verification, before any ZIP expansion.
private const val MAX_JAR_BYTES = 32 * 1024 * 1024

// A stalled source should be a finding rather than keeping preflight or startup waiting indefinitely.
private const val MOD_TIMEOUT_MS = 10_000

// HTTP defines successful responses as 2xx, inclusive of both endpoints.
private const val HTTP_OK_START = 200

// The final status in HTTP's successful class.
private const val HTTP_OK_END = 299
private val HTTP_SCHEMES = setOf("http", "https")
