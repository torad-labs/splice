package splice.app.observability

import splice.core.GATEWAY_VERSION
import splice.core.perf.observation.PerfIdentity
import java.io.IOException
import java.io.InputStream
import java.net.JarURLConnection
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.jar.JarFile

/** Reads the class loader's already opened artifact, never repository git or a replacement installed path. */
internal object PerfBuildIdentity {
    fun create(revision: String): PerfIdentity = PerfIdentity(
        UUID.randomUUID().toString(),
        artifact(),
        GATEWAY_VERSION,
        revision,
    )

    private fun artifact(): String = try {
        val resource = ClassLoader.getSystemResource("splice/app/observability/PerfBuildIdentity.class")
        val connection = resource?.openConnection() as? JarURLConnection
        if (connection == null) {
            "unknown:unpackaged_classes"
        } else {
            // Do not close a JarFile borrowed from the application's class loader.
            fingerprint(connection.jarFile)
        }
    } catch (_: IOException) {
        "unknown:artifact_unreadable"
    } catch (_: SecurityException) {
        "unknown:artifact_inaccessible"
    }

    private fun fingerprint(jar: JarFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        jar.entries().asSequence().filterNot { it.isDirectory }.sortedBy { it.name }.forEach { entry ->
            digest.update(entry.name.toByteArray(Charsets.UTF_8))
            jar.getInputStream(entry).use { input -> append(input, buffer, digest) }
        }
        return "jar-content-sha256:${HexFormat.of().formatHex(digest.digest())}"
    }

    private fun append(input: InputStream, buffer: ByteArray, digest: MessageDigest) {
        var read = input.read(buffer)
        while (read >= 0) {
            digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
    }
}

private const val BUFFER_BYTES = 16 * 1_024
