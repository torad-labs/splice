package splice.observability.profile

import jdk.jfr.FlightRecorder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.runtime.PerfProfileRequest
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

class ProfileManagerTest {
    private val identity = PerfIdentity("boot", "build", "version", "config")

    @Test
    fun `jfr stops to a bounded managed artifact and rejects parallel captures`(@TempDir directory: Path) {
        ProfileManager(directory, identity).use { manager ->
            val started = manager.start(PerfProfileRequest())
            val id = started.getValue("profile_id").jsonPrimitive.content
            assertEquals("running", started.getValue("status").jsonPrimitive.content)
            assertEquals("busy", manager.start(PerfProfileRequest()).getValue("status").jsonPrimitive.content)
            manager.stop(id)
            val completed = awaitSettled(manager, id)
            assertEquals("completed", completed.getValue("status").jsonPrimitive.content)
            val artifact = assertNotNull(manager.artifact(id)).let { checkNotNull(manager.artifact(id)) }
            assertTrue(artifact.sizeBytes in 1..ProfileLimits.ARTIFACT_BYTES)
            assertEquals(Files.size(artifact.path), artifact.sizeBytes)
            assertNull(manager.artifact("../../etc/passwd"))
            assertFalse(FlightRecorder.getFlightRecorder().recordings.any { it.name == "splice-performance" })
        }
    }

    @Test
    fun `unavailable modes and out of range durations return metadata without a capture`(@TempDir directory: Path) {
        ProfileManager(directory, identity, directory.resolve("missing-asprof")).use { manager ->
            for (request in listOf(
                PerfProfileRequest("native"),
                PerfProfileRequest(mode = "wall"),
                PerfProfileRequest(durationSeconds = 301),
            )) {
                val reply = manager.start(request)
                assertEquals("unsupported", reply.getValue("status").jsonPrimitive.content)
                assertTrue(reply.containsKey("identity"))
                assertEquals("false", reply.getValue("artifact_available").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `final artifact bytes are enforced independently of recording chunk limits`(@TempDir directory: Path) {
        val factory = ProfileCaptureFactory { _, _, _ ->
            object : ProfileCapture {
                override fun start() = Unit
                override fun finish(destination: Path) {
                    Files.newByteChannel(destination, StandardOpenOption.WRITE).use {
                        it.position(ProfileLimits.ARTIFACT_BYTES)
                        it.write(ByteBuffer.wrap(byteArrayOf(1)))
                    }
                }
                override fun close() = Unit
            }
        }
        ProfileManager(directory, identity, null, WallClock(System::currentTimeMillis), factory).use { manager ->
            val id = manager.start(PerfProfileRequest()).getValue("profile_id").jsonPrimitive.content
            manager.stop(id)
            assertEquals("oversized", awaitSettled(manager, id).getValue("status").jsonPrimitive.content)
            assertNull(manager.artifact(id))
            assertTrue(Files.list(directory).use { files -> files.noneMatch { it.toString().endsWith(".profile") } })
        }
    }

    @Test
    fun `failed capture publishes failure metadata and no artifact`(@TempDir directory: Path) {
        val factory = ProfileCaptureFactory { _, _, _ ->
            object : ProfileCapture {
                override fun start() { throw IOException("fixture failure") }
                override fun finish(destination: Path) = Unit
                override fun close() = Unit
            }
        }
        ProfileManager(directory, identity, factory = factory).use { manager ->
            val id = manager.start(PerfProfileRequest()).getValue("profile_id").jsonPrimitive.content
            val failed = awaitSettled(manager, id)
            assertEquals("failed", failed.getValue("status").jsonPrimitive.content)
            assertTrue(failed.getValue("issues").toString().contains("profile_io_or_control_failed"))
            assertNull(manager.artifact(id))
            PerfWorkBudget.release(failed)
        }
    }

    @Test
    fun `expired artifact is unavailable with an explicit retention reason`(@TempDir directory: Path) {
        var now = 1_000L
        val factory = ProfileCaptureFactory { _, _, _ ->
            object : ProfileCapture {
                override fun start() = Unit
                override fun finish(destination: Path) { Files.write(destination, byteArrayOf(1)) }
                override fun close() = Unit
            }
        }
        ProfileManager(directory, identity, clock = WallClock { now }, factory = factory).use { manager ->
            val id = manager.start(PerfProfileRequest()).getValue("profile_id").jsonPrimitive.content
            PerfWorkBudget.release(manager.stop(id))
            PerfWorkBudget.release(awaitSettled(manager, id))
            assertNotNull(manager.artifact(id))
            now += ProfileLimits.RETENTION_MS + 1
            val expired = manager.status(id)
            assertNull(manager.artifact(id))
            assertEquals("false", expired.getValue("artifact_available").jsonPrimitive.content)
            assertTrue(expired.getValue("issues").toString().contains("artifact_expired"))
            PerfWorkBudget.release(expired)
        }
    }

    @Test
    fun `close aborts an active recording and leaves no artifact`(@TempDir directory: Path) {
        val manager = ProfileManager(directory, identity)
        manager.start(PerfProfileRequest(durationSeconds = 300))
        manager.close()
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            while (Files.list(directory).use { it.anyMatch { path -> path.toString().endsWith(".tmp") } } ||
                FlightRecorder.getFlightRecorder().recordings.any { it.name == "splice-performance" }
            ) {
                check(!Thread.currentThread().isInterrupted) { "Capture cleanup deadline expired" }
                Thread.yield()
            }
        }
        assertTrue(Files.list(directory).use { it.noneMatch { path -> path.toString().endsWith(".profile") } })
    }

    private fun awaitSettled(manager: ProfileManager, id: String): JsonObject =
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            var state = manager.status(id)
            while (state.getValue("status").jsonPrimitive.content == "running") {
                check(!Thread.currentThread().isInterrupted) { "Profile completion deadline expired" }
                Thread.yield()
                PerfWorkBudget.release(state)
                state = manager.status(id)
            }
            state
        }
}
