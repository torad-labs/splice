// NEW: KimiDeviceIdentity pins — the five X-Msh-* headers, ASCII sanitization (CJK hostname
// stripped; empty result -> "unknown"; Ktor throws on non-Latin1 header values), and the persisted
// device_id (uuid, stable across calls, 0600).
package kimi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.kimi.KimiDeviceIdentity
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class KimiDeviceIdentityTest {

    @Test
    fun `headers carry the five X-Msh values and platform is splice`() {
        val dir = Files.createTempDirectory("kimi-id")
        val id = KimiDeviceIdentity(
            deviceIdPath = dir.resolve("device_id"),
            version = "kt-1",
            rawHostname = "my-host",
            osName = "Mac OS X",
            osVersion = "14.6",
            osArch = "aarch64",
        )
        val headers = id.headers()
        assertEquals("splice", headers["X-Msh-Platform"])
        assertEquals("kt-1", headers["X-Msh-Version"])
        assertEquals("my-host", headers["X-Msh-Device-Name"])
        assertEquals("Mac OS X 14.6 aarch64", headers["X-Msh-Device-Model"])
        assertEquals("14.6", headers["X-Msh-Os-Version"])
    }

    @Test
    fun `non-ASCII header values are stripped and empty becomes unknown`() {
        val dir = Files.createTempDirectory("kimi-id-cjk")
        val id = KimiDeviceIdentity(
            deviceIdPath = dir.resolve("device_id"),
            rawHostname = "主机名", // pure CJK -> stripped to empty -> "unknown"
            osName = "Linux-测试",
            osVersion = "6.1",
            osArch = "x86_64",
        )
        val headers = id.headers()
        assertEquals("unknown", headers["X-Msh-Device-Name"])
        assertEquals("Linux- 6.1 x86_64", headers["X-Msh-Device-Model"]) // CJK stripped from os.name
        // every header value is Latin1-safe (Ktor requirement)
        assertTrue(headers.values.all { v -> v.all { it.code < 0x80 } })
    }

    @Test
    fun `device id is a stable uuid persisted at 0600`() {
        val dir = Files.createTempDirectory("kimi-id-persist")
        val path = dir.resolve("device_id")
        val id = KimiDeviceIdentity(deviceIdPath = path)
        val first = id.deviceId()
        assertTrue(first.isNotBlank())
        assertEquals(first, id.deviceId()) // stable across calls
        assertEquals(first, KimiDeviceIdentity(deviceIdPath = path).deviceId()) // stable across instances
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
    }
}
