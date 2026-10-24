// NEW: the worker class's owning archive, not an install pathname or a launcher/pathing jar.
package splice.codemode

import splice.core.config.StatePaths
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipException

private const val WORKER_CLASS_RESOURCE = "splice/codemode/CodeModeWorker.class"

/** Process-wide pins are captured synchronously before prewarm and reused across runtime replacements. */
public object WorkerArtifacts {
    private val pins by lazy { WorkerArtifactPins(StatePaths().stateDir) }
    private val running by lazy {
        val resource = ClassLoader.getSystemResource(WORKER_CLASS_RESOURCE)
        if (resource?.protocol == "jar") {
            val archive = Path.of(URI.create(resource.path.substringBefore("!/")))
            val dependencies = System.getProperty("java.class.path").split(File.pathSeparator)
                .filterNot { Path.of(it).toAbsolutePath().normalize() == archive.toAbsolutePath().normalize() }
            (listOf(verifiedArchive(archive).toString()) + dependencies).joinToString(File.pathSeparator)
        } else {
            System.getProperty("java.class.path")
        }
    }

    /** Capture before daemon heads start, rather than when their first script constructs a runtime. */
    public fun pinAtBoot() {
        check(runningClasspath().isNotBlank()) { "Worker archive classpath is required" }
    }

    internal fun runningClasspath(): String = running

    /** Development directories stay intact; explicit archive fixtures pin the archive owning the worker. */
    internal fun pinClasspath(classpath: String): String =
        classpath.split(File.pathSeparator).joinToString(File.pathSeparator, transform = ::pinEntry)

    private fun pinEntry(entry: String): String {
        val path = Path.of(entry)
        if (!Files.isRegularFile(path)) return entry
        return if (ownsWorker(path)) pins.pin(path).toString() else entry
    }

    private fun ownsWorker(path: Path): Boolean = try {
        JarFile(path.toFile()).use { it.getJarEntry(WORKER_CLASS_RESOURCE) != null }
    } catch (_: ZipException) {
        // Java ignores non-archive files on a classpath too, regardless of their filename suffix.
        false
    }

    private fun verifiedArchive(archive: Path): Path {
        val loaded = WorkerArchiveStamp.fingerprint()
        val entries = WorkerArchiveStamp.entries()
        val pinned = pins.pin(archive)
        val captured = JarFile(pinned.toFile()).use { jar ->
            val digest = MessageDigest.getInstance("SHA-256")
            for (name in entries) {
                val entry = jar.getJarEntry(name)
                    ?: throw IOException("The jar at $archive lost $name before code mode pinned it; restart splice")
                digest.update(name.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                jar.getInputStream(entry).use { digest.update(it.readBytes()) }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (loaded != captured) {
            throw IOException("The jar at $archive was replaced before code mode pinned it; restart splice")
        }
        return pinned
    }
}
