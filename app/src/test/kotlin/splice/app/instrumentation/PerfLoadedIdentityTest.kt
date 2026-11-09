package splice.app.instrumentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.ApplicationArtifactStamp
import splice.core.perf.observation.PerfIdentity
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class PerfLoadedIdentityTest {
    @Test
    fun `returned identity stays attached to loaded classes after their artifact is atomically replaced`(
        @TempDir dir: Path,
    ) {
        val original = ApplicationArtifactStamp.fingerprint()
        val replacement = if (original == "a".repeat(64)) "b".repeat(64) else "a".repeat(64)
        val installed = dir.resolve("application.jar")
        archive(installed, original, original)
        LoadedIdentity(installed).use { running ->
            val first = running.identity()
            assertEquals("compiled-artifact-inputs-sha256:$original", first.build)
            val updated = dir.resolve("replacement.jar")
            archive(updated, original, replacement)
            Files.move(updated, installed, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            assertEquals(first.build, running.identity().build)
            LoadedIdentity(installed).use { restarted ->
                assertNotEquals(first.build, restarted.identity().build)
                assertEquals("compiled-artifact-inputs-sha256:$replacement", restarted.identity().build)
            }
        }
    }

    private fun archive(path: Path, original: String, revision: String) {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            CLASSES.forEach { name ->
                val resource = name.replace('.', '/') + ".class"
                val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream(resource)).use { it.readBytes() }
                val output = if (name.startsWith(
                        "splice.app.ApplicationArtifact",
                    )
                ) {
                    replaceConstant(bytes, original, revision)
                } else {
                    bytes
                }
                jar.putNextEntry(JarEntry(resource))
                jar.write(output)
                jar.closeEntry()
            }
        }
    }

    private fun replaceConstant(bytes: ByteArray, original: String, revision: String): ByteArray {
        val marker = original.toByteArray(Charsets.US_ASCII)
        val at = (0..bytes.size - marker.size).single { offset ->
            marker.indices.all { bytes[offset + it] == marker[it] }
        }
        revision.toByteArray(Charsets.US_ASCII).copyInto(bytes, at)
        return bytes
    }

    private class LoadedIdentity(path: Path) : URLClassLoader(
        arrayOf(path.toUri().toURL()),
        PerfIdentity::class.java.classLoader,
    ) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            val loaded = if (name in CLASSES) {
                findLoadedClass(
                    name,
                ) ?: findClass(name)
            } else {
                super.loadClass(name, resolve)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }

        fun identity(): PerfIdentity {
            val type = loadClass("splice.app.observability.PerfBuildIdentity")
            return type.getMethod(
                "create",
                String::class.java,
            ).invoke(type.getField("INSTANCE").get(null), "fixture") as PerfIdentity
        }
    }
}

private val CLASSES = setOf(
    "splice.app.ApplicationArtifactStamp",
    "splice.app.ApplicationArtifactFingerprint",
    "splice.app.observability.PerfBuildIdentity",
)
