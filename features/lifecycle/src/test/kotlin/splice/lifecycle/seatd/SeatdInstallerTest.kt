package splice.lifecycle.seatd

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.UserHome
import splice.core.util.EnvReader
import splice.lifecycle.upgrade.UpgradeFetch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat

class SeatdInstallerTest(@TempDir scratch: Path) {
    private val home = scratch
    private val share = home.resolve("share with spaces")
    private val installer = SeatdInstaller(EnvReader { key -> share.toString().takeIf { key == "SPLICE_SHARE_DIR" } })
    private val release = home.resolve("upstream").resolve("3.0.0")
    private val asset = release.resolve("renamed-pipe")
    private val binary = "stand-in executable".toByteArray()
    private val target = share.resolve("seatd/3.0.0/seatd-pipe")
    private val retry = "splice desktop on"
    private val platform = SeatdPlatform()
    private val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary))
    private val pin = SeatdPin(
        version = "3.0.0",
        urlTemplate = home.resolve("upstream").toUri().toString().trimEnd('/') + "/{version}/{asset}",
        platforms = mapOf(requireNotNull(platform.key()) to SeatdAsset("renamed-pipe", digest)),
    )

    @Test
    fun `a verified download lands executable at the versioned path and can be found there`() = inHome {
        assertNull(installer.installed(pin, platform))
        publish(binary)

        val result = assertInstanceOf(SeatdInstaller.Result.Installed::class.java, install())

        assertEquals(SeatdInstaller.Location(target, "3.0.0"), result.location)
        assertEquals(result.location, installer.installed(pin, platform))
        assertArrayEquals(binary, Files.readAllBytes(target))
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(target))
        assertFalse(Files.exists(target.resolveSibling(".seatd-pipe.part")))
    }

    @Test
    fun `a checksum mismatch never lands and tells the person how to retry`() = inHome {
        publish("wrong bytes".toByteArray())

        failed(install(), SeatdInstaller.Failure.CHECKSUM_MISMATCH, "checksum", retry)
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(target.resolveSibling(".seatd-pipe.part")))
    }

    @Test
    fun `a refusing download does not look like a permissions failure`() = inHome {
        failed(install(), SeatdInstaller.Failure.DOWNLOAD_FAILED, "download", retry)
        assertFalse(Files.exists(target))
    }

    @Test
    fun `an unwritable version folder is named with the way to retry`() = inHome {
        publish(binary)
        Files.createDirectories(target.parent)
        Files.setPosixFilePermissions(target.parent, PosixFilePermissions.fromString("r-x------"))
        try {
            failed(install(), SeatdInstaller.Failure.NOT_WRITABLE, target.parent.toString(), retry)
            assertFalse(Files.exists(target))
        } finally {
            Files.setPosixFilePermissions(target.parent, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `an unreleased version refuses without creating an install folder`() = inHome {
        val placeholder = pin.copy(version = "0.0.0-unreleased")

        failed(
            installer.install(
                placeholder,
                platform,
                retry,
                UpgradeFetch { error("An unreleased pin must not download") },
            ),
            SeatdInstaller.Failure.NO_REVIEWED_RELEASE,
            "No reviewed seatd-pipe release is pinned yet",
            "https://github.com/torad-labs/seatd",
        )
        assertNull(installer.installed(placeholder, platform))
        assertFalse(Files.exists(share))
    }

    @Test
    fun `the shipped pin determines which local executable is reported`() = inHome {
        val bundled = requireNotNull(SeatdPins.bundled)
        assertNull(installer.installed())
        val path = version(bundled.version)

        val expected = SeatdInstaller.Location(path, bundled.version).takeIf { bundled.reviewed() }
        assertEquals(expected, installer.installed())
    }

    @Test
    fun `a release with placeholder checksums refuses before it can fetch anything`() = inHome {
        val unreviewed = "0".repeat(64)
        val placeholder = pin.copy(platforms = pin.platforms.mapValues { (_, row) -> row.copy(sha256 = unreviewed) })

        failed(
            installer.install(placeholder, platform, retry, UpgradeFetch { error("A placeholder must not download") }),
            SeatdInstaller.Failure.NO_REVIEWED_RELEASE,
            "No reviewed seatd-pipe release is pinned yet",
        )
        assertFalse(Files.exists(share))
    }

    @Test
    fun `an unsupported platform gets a clear refusal without creating an install folder`() = inHome {
        failed(
            installer.install(pin, SeatdPlatform("Windows", "amd64"), retry),
            SeatdInstaller.Failure.UNSUPPORTED_PLATFORM,
            "Windows",
            "https://github.com/torad-labs/seatd",
        )
        assertFalse(Files.exists(share))
    }

    @Test
    fun `an installed pinned version is kept without downloading or pruning again`() = inHome {
        val existing = version("1.0.0")
        Files.createDirectories(target.parent)
        Files.write(target, binary)
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))

        val result = assertInstanceOf(SeatdInstaller.Result.AlreadyInstalled::class.java, install())

        assertEquals(SeatdInstaller.Location(target, "3.0.0"), result.location)
        assertArrayEquals(binary, Files.readAllBytes(target))
        assertTrue(Files.exists(existing))
    }

    @Test
    fun `an install prunes inactive old versions but keeps rollback and a running older version`() = inHome {
        val rollback = version("2.10.0")
        val stale = version("2.9.0")
        val newer = version("4.0.0")
        val running = share.resolve("seatd/1.0.0/seatd-pipe")
        Files.createDirectories(running.parent)
        val session = startSession(running)
        try {
            assertTrue(session.isAlive)
            publish(binary)

            assertInstanceOf(SeatdInstaller.Result.Installed::class.java, install())
            assertTrue(Files.exists(rollback))
            assertFalse(Files.exists(stale.parent))
            assertTrue(Files.exists(newer))
            assertTrue(Files.exists(running))
            assertTrue(session.isAlive)
        } finally {
            session.destroyForcibly().waitFor()
        }
    }

    private fun startSession(path: Path): Process {
        if (platform.linux) {
            Files.copy(Path.of("/bin/sleep"), path)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        } else {
            // A copy of a macOS system binary does not retain permission to execute outside the system volume.
            val build = ProcessBuilder("/usr/bin/cc", "-x", "c", "-", "-o", path.toString())
                .redirectErrorStream(true).start()
            build.outputStream.bufferedWriter().use {
                it.write("#include <unistd.h>\nint main(void) { sleep(60); return 0; }\n")
            }
            val diagnostics = build.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, build.waitFor(), diagnostics)
        }
        return ProcessBuilder(path.toString(), "60").start()
    }

    private fun inHome(body: () -> Unit) = UserHome.within(home, body)

    private fun install(): SeatdInstaller.Result = installer.install(pin, platform, retry)

    private fun publish(bytes: ByteArray) {
        Files.createDirectories(release)
        Files.write(asset, bytes)
    }

    private fun version(version: String): Path {
        val path = share.resolve("seatd/$version/seatd-pipe")
        Files.createDirectories(path.parent)
        return Files.write(path, binary).also {
            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    private fun failed(result: SeatdInstaller.Result, kind: SeatdInstaller.Failure, vararg words: String) {
        val failure = assertInstanceOf(SeatdInstaller.Result.Failed::class.java, result)
        assertEquals(kind, failure.kind)
        words.forEach { assertTrue(failure.message.contains(it), failure.message) }
    }
}
