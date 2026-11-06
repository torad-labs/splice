package splice.observability.profile

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.runtime.PerfProfileRequest
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class ProfileDownloadTest {
    private val identity = PerfIdentity("boot", "build", "version", "config")
    private val payload = byteArrayOf(1, 2, 3, 4)
    private val factory = ProfileCaptureFactory { _, _, _ ->
        object : ProfileCapture {
            override fun start() = Unit
            override fun finish(destination: Path) { Files.write(destination, payload) }
            override fun close() = Unit
        }
    }

    @Test
    fun `download survives expiry and manager close while retaining the store owner`(@TempDir directory: Path) {
        val before = PerfWorkBudget.retainedBytes()
        var now = 1_000L
        val manager = ProfileManager(directory, identity, clock = WallClock { now }, factory = factory)
        try {
            val id = capture(manager)
            val reader = checkNotNull(manager.openArtifact(id))
            reader.use {
                val second = checkNotNull(manager.openArtifact(id))
                second.use { assertNull(manager.openArtifact(id)) }
                now += ProfileLimits.ARTIFACT_RETENTION_MS + 1
                capture(manager) // The normal capture path prunes expired records.
                assertNull(manager.openArtifact(id))
                assertTrue(Files.exists(reader.artifact.path))
                manager.close()
                assertNull(manager.openArtifact(id))
                assertTrue(PerfWorkBudget.retainedBytes() > before)
                assertThrows(IllegalStateException::class.java) {
                    ProfileManager(directory, identity, factory = factory).close()
                }
                val bytes = ByteArray(payload.size)
                assertEquals(payload.size, reader.read(bytes))
                assertArrayEquals(payload, bytes)
                assertEquals(-1, reader.read(bytes))
            }
            assertEquals(before, PerfWorkBudget.retainedBytes())
            ProfileManager(
                directory,
                identity,
                factory = factory,
            ).use { assertNotNull(it.capabilities().also(::release)) }
        } finally {
            manager.close()
        }
        assertEquals(before, PerfWorkBudget.retainedBytes())
    }

    private fun capture(manager: ProfileManager): String = assertTimeoutPreemptively<String>(Duration.ofSeconds(10)) {
        var started = manager.start(PerfProfileRequest())
        while (started.getValue("status").jsonPrimitive.content == "busy") {
            release(started)
            check(!Thread.currentThread().isInterrupted)
            Thread.yield()
            started = manager.start(PerfProfileRequest())
        }
        val id = started.getValue("profile_id").jsonPrimitive.content
        assertEquals("running", started.getValue("status").jsonPrimitive.content)
        release(started)
        release(manager.stop(id))
        var state = manager.status(id)
        while (state.getValue("status").jsonPrimitive.content == "running") {
            release(state)
            check(!Thread.currentThread().isInterrupted)
            Thread.yield()
            state = manager.status(id)
        }
        assertEquals("completed", state.getValue("status").jsonPrimitive.content)
        release(state)
        id
    }

    private fun release(reply: JsonObject) { PerfWorkBudget.release(reply) }
}
