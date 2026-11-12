// NEW: pin integrity and portable daemon ownership are tested without process filesystem dependencies.
package splice.codemode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.process.LaunchProcess
import splice.core.process.LaunchProcessIdentity
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.Instant

class WorkerArtifactPinsTest {
    @Test
    fun `a pin is named by the opened bytes and hardlinked when possible`(@TempDir root: Path) {
        val source = Files.writeString(root.resolve("source.jar"), "original")
        val pins = WorkerArtifactPins(root.resolve("state"))
        val pinned = pins.pin(source)
        val expected = MessageDigest.getInstance("SHA-256").digest("original".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("$expected.jar", pinned.fileName.toString())
        assertTrue(Files.isSameFile(source, pinned))
        assertEquals(pinned, pins.pin(source))
    }

    @Test
    fun `a link to a swapped pathname is replaced by a copy of the opened archive`(@TempDir root: Path) {
        val source = Files.writeString(root.resolve("source.jar"), "original")
        val replacement = Files.writeString(root.resolve("next.jar"), "new installation")
        val pins = WorkerArtifactPins(
            root.resolve("state"),
            link = WorkerArchiveLink { target, pathname ->
                Files.move(replacement, pathname, ATOMIC_MOVE, REPLACE_EXISTING)
                Files.createLink(target, pathname)
            },
        )
        val pinned = pins.pin(source)
        assertEquals("original", Files.readString(pinned))
        assertEquals("new installation", Files.readString(source))
        assertFalse(Files.isSameFile(source, pinned))
    }

    @Test
    fun `unsupported and cross-filesystem links copy the opened archive`(@TempDir root: Path) {
        val source = Files.writeString(root.resolve("source.jar"), "original")
        val failures = listOf(IOException("cross-device link"), UnsupportedOperationException("no hardlinks"))
        failures.forEachIndexed { index, failure ->
            val pins = WorkerArtifactPins(
                root.resolve("state-$index"),
                link = WorkerArchiveLink { _, _ -> throw failure },
            )
            val pinned = pins.pin(source)
            assertEquals("original", Files.readString(pinned))
            assertFalse(Files.isSameFile(source, pinned))
        }
    }

    @Test
    fun `a symlink source pins its bytes rather than following later target installs`(@TempDir root: Path) {
        val source = Files.writeString(root.resolve("source.jar"), "original")
        val alias = Files.createSymbolicLink(root.resolve("alias.jar"), source.fileName)
        val pinned = WorkerArtifactPins(root.resolve("state")).pin(alias)
        val replacement = Files.writeString(root.resolve("next.jar"), "replacement")
        Files.move(replacement, source, ATOMIC_MOVE, REPLACE_EXISTING)
        assertEquals("original", Files.readString(pinned))
        assertFalse(Files.isSymbolicLink(pinned))
    }

    @Test
    fun `a failed partial copy leaves no published pin and a retry succeeds`(@TempDir root: Path) {
        val source = Files.writeString(root.resolve("source.jar"), "original")
        var attempts = 0
        val pins = WorkerArtifactPins(
            root.resolve("state"),
            link = WorkerArchiveLink { _, _ -> throw IOException("no hardlink") },
            copy = WorkerArchiveCopy { input, target ->
                if (attempts++ == 0) {
                    Files.writeString(target, "partial")
                    throw IOException("copy interrupted")
                }
                Files.copy(input, target, REPLACE_EXISTING)
            },
        )
        assertThrows(IOException::class.java) { pins.pin(source) }
        val filesAfterFailure = Files.walk(root.resolve("state")).use { paths ->
            paths.filter(Files::isRegularFile).count()
        }
        assertEquals(0L, filesAfterFailure)
        assertEquals("original", Files.readString(pins.pin(source)))
    }

    @Test
    fun `boot reaps gone and reused owners but preserves live owners and unknown births`(
        @TempDir root: Path,
    ) {
        val artifactRoot = root.resolve("worker-artifacts")
        val live = Files.createDirectories(artifactRoot.resolve("101-1-0"))
        val gone = Files.createDirectories(artifactRoot.resolve("102-1-0"))
        val reused = Files.createDirectories(artifactRoot.resolve("103-1-0"))
        val unknown = Files.createDirectories(artifactRoot.resolve("104-unknown"))
        val birthUnavailable = Files.createDirectories(artifactRoot.resolve("105-1-0"))
        val paths = listOf(live, gone, reused, unknown, birthUnavailable)
        paths.forEach { Files.writeString(it.resolve("pin.jar"), "archive") }
        WorkerArtifactPins(
            root,
            ownerPid = 106,
            ownerBirth = Instant.ofEpochSecond(2),
            processes = LaunchProcessIdentity { pid ->
                when (pid) {
                    101L -> LaunchProcess(Instant.ofEpochSecond(1))
                    103L -> LaunchProcess(Instant.ofEpochSecond(2))
                    104L -> LaunchProcess(Instant.ofEpochSecond(1))
                    105L -> LaunchProcess(null)
                    else -> null
                }
            },
        )
        assertTrue(Files.exists(live.resolve("pin.jar")))
        assertFalse(Files.exists(gone))
        assertFalse(Files.exists(reused))
        assertTrue(Files.exists(unknown.resolve("pin.jar")))
        assertTrue(Files.exists(birthUnavailable.resolve("pin.jar")))
    }
}
