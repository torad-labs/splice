package splice.lifecycle.seatd

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.GATEWAY_VERSION
import splice.core.config.UserHome
import splice.core.install.seatdPipeRepository
import splice.core.util.EnvReader
import splice.lifecycle.upgrade.UpgradeFetch
import splice.lifecycle.upgrade.UpgradeFetchFailed
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat

class SeatdInstallerTest(@TempDir scratch: Path) {
    private val home = scratch
    private val share = home.resolve("share with spaces")
    private val installer = SeatdInstaller(EnvReader { key -> share.toString().takeIf { key == "SPLICE_SHARE_DIR" } })
    private val release = home.resolve("released-splice")
    private val asset = release.resolve("seatd-pipe-renamed.tar.gz")
    private val binary = "stand-in executable".toByteArray()
    private val archives = SeatdArchiveFixture(binary)
    private val archive = archives.bytes()
    private val target = share.resolve("seatd/3.0.0/seatd-pipe")
    private val retry = "splice desktop on"
    private val platform = SeatdPlatform()
    private val pin = requireNotNull(SeatdPins.bundled).copy(
        version = "3.0.0",
        platforms = mapOf(requireNotNull(platform.key()) to SeatdAsset(asset.fileName.toString(), sha256(archive))),
    )

    @Test
    fun `a verified download lands executable at the versioned path and can be found there`() = inHome {
        assertNull(installer.installed(pin, platform))
        publish(archive)

        val result = assertInstanceOf(SeatdInstaller.Result.Installed::class.java, install())

        assertEquals(SeatdInstaller.Location(target, "3.0.0"), result.location)
        assertEquals(result.location, installer.installed(pin, platform))
        assertArrayEquals(binary, Files.readAllBytes(target))
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(target))
        assertEquals(archives.license, Files.readString(target.resolveSibling("LICENSE")))
        assertEquals(archives.notices, Files.readString(target.resolveSibling("THIRD_PARTY_NOTICES.md")))
    }

    @Test
    fun `the pinned asset installs from the running Splice version's release`() = inHome {
        val bundled = requireNotNull(SeatdPins.bundled)
        val staged = System.getProperty("splice.seatdRehearsalDist")?.let(Path::of)
        val sums = staged?.let {
            Files.readAllLines(it.resolve("sha256sums.txt")).associate { line ->
                val (sum, name) = line.split("  ", limit = 2)
                name to sum
            }
        }
        val reviewedAssets = bundled.platforms.mapValues { (_, row) ->
            row.copy(sha256 = sums?.getValue(row.asset) ?: sha256(archive))
        }
        val reviewed = bundled.copy(platforms = reviewedAssets)
        val row = reviewed.platforms.getValue(requireNotNull(platform.key()))
        val bytes = staged?.let { Files.readAllBytes(it.resolve(row.asset)) } ?: archive
        val fetch = UpgradeFetch { url ->
            assertEquals("https://splice.torad.ai/download/v$GATEWAY_VERSION/${row.asset}", url)
            bytes
        }

        val result = installer.install(reviewed, platform, retry, fetch)
        val installed = assertInstanceOf(SeatdInstaller.Result.Installed::class.java, result)
        val path = share.resolve("seatd/${bundled.version}/seatd-pipe")
        assertEquals(SeatdInstaller.Location(path, bundled.version), installed.location)
        assertTrue(Files.isExecutable(path))
    }

    @Test
    fun `a checksum mismatch never lands and tells the person how to retry`() = inHome {
        publish("wrong bytes".toByteArray())

        failed(install(), SeatdInstaller.Failure.CHECKSUM_MISMATCH, "checksum", retry)
        assertFalse(Files.exists(target.parent))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "path traversal", "absolute path", "symlink", "device", "extra file", "missing program", "pax metadata",
        ],
    )
    fun `a verified archive with an invalid inventory installs nothing`(kind: String) = inHome {
        val bytes = archives.bytes(kind)
        val edited = pin.copy(platforms = pin.platforms.mapValues { (_, row) -> row.copy(sha256 = sha256(bytes)) })
        publish(bytes)

        val result = assertInstanceOf(
            SeatdInstaller.Result.Failed::class.java,
            installer.install(edited, platform, retry, releaseAsset()),
        )
        assertEquals(SeatdInstaller.Failure.INVALID_ARCHIVE, result.kind)
        assertTrue(result.message.contains("archive"), result.message)
        assertTrue(result.message.contains("Nothing was installed"), result.message)
        assertFalse(Files.exists(target.parent))
        assertFalse(Files.exists(share.resolve("seatd/x")))
    }

    @Test
    fun `a missing exact release directs source builds to a released Splice without falling back`() = inHome {
        failed(
            install(),
            SeatdInstaller.Failure.DOWNLOAD_FAILED,
            "No published Splice $GATEWAY_VERSION release with seatd-pipe",
            "Install a released Splice",
            "https://splice.torad.ai/download",
            retry,
        )
        assertFalse(Files.exists(target))
    }

    @Test
    fun `a network failure gives retry advice without exposing its exception`() = inHome {
        val fetch = UpgradeFetch { throw UpgradeFetchFailed("internal transport detail") }
        val result = installer.install(pin, platform, retry, fetch)
        failed(result, SeatdInstaller.Failure.DOWNLOAD_FAILED, "connection", retry)
        assertFalse(assertInstanceOf(SeatdInstaller.Result.Failed::class.java, result).message.contains("internal"))
        assertFalse(Files.exists(target))
    }

    @Test
    fun `an unwritable version folder is named with the way to retry`() = inHome {
        publish(archive)
        val folder = Files.createDirectories(target.parent.parent)
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"))
        try {
            assumeFalse(Files.isWritable(folder), "This test needs a user subject to directory permissions.")
            failed(install(), SeatdInstaller.Failure.NOT_WRITABLE, folder.toString(), retry)
            assertFalse(Files.exists(target.parent))
        } finally {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"))
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
            seatdPipeRepository,
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
            seatdPipeRepository,
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
            val reply = session.inputStream.bufferedReader()
            assertEquals("ready", reply.readLine())
            publish(archive)

            assertInstanceOf(SeatdInstaller.Result.Installed::class.java, install())
            assertTrue(Files.exists(rollback))
            assertFalse(Files.exists(stale.parent))
            assertTrue(Files.exists(newer))
            assertTrue(Files.exists(running))
            assertTrue(session.isAlive)
            session.outputStream.bufferedWriter().use { it.newLine() }
            assertEquals("1.0.0", reply.readLine())
            assertEquals(0, session.waitFor())
        } finally {
            session.destroyForcibly().waitFor()
        }
    }

    private fun startSession(path: Path): Process {
        // Compile a real executable: system sleep may be a multicall binary or restricted to its original location.
        val build = ProcessBuilder("cc", "-x", "c", "-", "-o", path.toString()).redirectErrorStream(true).start()
        build.outputStream.bufferedWriter().use {
            it.write(
                """
                #include <stdio.h>
                int main(void) {
                    puts("ready");
                    fflush(stdout);
                    if (getchar() == EOF) return 1;
                    puts("1.0.0");
                    return 0;
                }
                """.trimIndent(),
            )
        }
        val diagnostics = build.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, build.waitFor(), diagnostics)
        return ProcessBuilder(path.toString()).start()
    }

    private fun inHome(body: () -> Unit) = UserHome.within(home, body)

    private fun install(): SeatdInstaller.Result = installer.install(pin, platform, retry, releaseAsset())

    private fun releaseAsset(): UpgradeFetch = UpgradeFetch { url ->
        assertEquals("https://splice.torad.ai/download/v$GATEWAY_VERSION/${asset.fileName}", url)
        if (Files.exists(asset)) Files.readAllBytes(asset) else null
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

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
