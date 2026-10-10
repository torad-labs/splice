// `splice upgrade` run as a process: a child JVM on the app's main class, HOME and the share dir in a temp folder,
// a file:// release, and fake systemctl and java scripts first on PATH (java through SPLICE_UPGRADE_JAVA, since the
// verb runs the candidate with its own JVM). A small HTTP server answers /health with the version the new release
// reports, as the restarted daemon would. What is read is the exit code, the printed lines and the install on disk.
package splice.app.cli

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.GATEWAY_VERSION
import splice.core.testing.TestPorts
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private val SELECTOR_PREFIXES = listOf("SPLICE_", "CLAUDEX_", "XDG_", "CONTROL_")
private const val CHILD_SECONDS = 120L
private const val NEW_VERSION = "9.9.9"
private const val SHIM = "#!/bin/sh\necho stock\n"

class UpgradeProcessTest {

    private class Ran(val exit: Int, val stdout: String)

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun script(path: Path, body: String): Path {
        Files.writeString(path, "#!/bin/sh\n$body\n")
        path.toFile().setExecutable(true)
        return path
    }

    /** A flat install under [home]/share, as install.sh wrote it before 0.4.0, and a release to fetch. */
    private fun install(home: Path): Path {
        val share = Files.createDirectories(home.resolve("share"))
        Files.writeString(share.resolve("splice.jar"), "old-jar")
        Files.writeString(share.resolve("splice-launch"), SHIM)
        val release = Files.createDirectories(home.resolve("release"))
        val assets = mapOf(
            "splice.jar" to "new-jar".toByteArray(),
            "splice-launch" to "#!/bin/sh\necho new\n".toByteArray(),
        )
        assets.forEach { (name, bytes) -> Files.write(release.resolve(name), bytes) }
        Files.writeString(
            release.resolve("sha256sums.txt"),
            assets.entries.joinToString("") { (name, bytes) -> "${sha(bytes)}  $name\n" },
        )
        return release
    }

    private fun upgrade(tmp: Path, reports: String, doctorExit: Int, vararg args: String): Ran {
        val home = Files.createDirectories(tmp.resolve("home"))
        val release = install(home)
        val bin = Files.createDirectories(tmp.resolve("fake-bin"))
        script(
            bin.resolve("systemctl"),
            "case \"\$3\" in show) echo \"java -jar ${home.resolve("share/splice.jar")} daemon\";; " +
                "is-active) echo active;; esac",
        )
        val java = script(
            bin.resolve("java"),
            "for a; do last=\$a; done\n" +
                "case \"\$last\" in version) echo \"splice $reports\";; --json) echo '{}';; doctor) exit $doctorExit;; esac",
        )
        val health = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        health.createContext("/health") { exchange ->
            val body = """{"version":"$NEW_VERSION","ok":true,"heads":1,"readyHeads":1,"failedHeads":0}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        health.start()
        val output = tmp.resolve("upgrade.out")
        val builder = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-Duser.home=${Files.createDirectories(tmp.resolve("passwd-home"))}",
            "-cp",
            System.getProperty("java.class.path"),
            "splice.app.MainKt",
            "upgrade",
            *args,
        ).directory(tmp.toFile()).redirectErrorStream(true).redirectOutput(output.toFile())
        builder.environment().apply {
            keys.removeIf { key -> SELECTOR_PREFIXES.any(key::startsWith) }
            put("HOME", home.toString())
            put("PATH", "$bin:${System.getenv("PATH")}")
            put("SPLICE_SHARE_DIR", home.resolve("share").toString())
            put("SPLICE_BIN_DIR", home.resolve("bin").toString())
            put("SPLICE_RELEASE_BASE_URL", release.toUri().toString().trimEnd('/'))
            put("SPLICE_UPGRADE_JAVA", java.toString())
            put("SPLICE_CONTROL_PORT", health.address.port.toString())
        }
        val process = builder.start()
        try {
            assertTrue(process.waitFor(CHILD_SECONDS, TimeUnit.SECONDS), "splice upgrade ran past $CHILD_SECONDS s")
        } finally {
            process.destroyForcibly()
            health.stop(0)
        }
        return Ran(process.exitValue(), Files.readString(output))
    }

    private fun share(tmp: Path, name: String): Path = tmp.resolve("home/share").resolve(name)

    @Test
    fun `an upgrade to the installed release changes nothing`(@TempDir tmp: Path) {
        val ran = upgrade(tmp, GATEWAY_VERSION, 0, "--to", "v$GATEWAY_VERSION", "--now")

        assertEquals(0, ran.exit, ran.stdout)
        assertTrue(ran.stdout.contains("$GATEWAY_VERSION is already installed"), ran.stdout)
        assertEquals("old-jar", Files.readString(share(tmp, "splice.jar")), "the live jar is untouched")
    }

    @Test
    fun `an upgrade swaps the release and keeps the previous one`(@TempDir tmp: Path) {
        val ran = upgrade(tmp, NEW_VERSION, 0, "--to", "v$NEW_VERSION", "--now")

        assertEquals(0, ran.exit, ran.stdout)
        assertTrue(ran.stdout.contains("serving $NEW_VERSION"), ran.stdout)
        assertEquals("new-jar", Files.readString(share(tmp, "splice.jar")))
        assertEquals(NEW_VERSION, Files.readSymbolicLink(share(tmp, "releases/current")).toString())
        assertEquals(GATEWAY_VERSION, Files.readSymbolicLink(share(tmp, "releases/previous")).toString())
        assertEquals("old-jar", Files.readString(share(tmp, "releases/$GATEWAY_VERSION/splice.jar")))
    }

    @Test
    fun `a red doctor after the upgrade undoes nothing`(@TempDir tmp: Path) {
        val ran = upgrade(tmp, NEW_VERSION, 1, "--to", "v$NEW_VERSION", "--now")

        assertEquals(0, ran.exit, ran.stdout)
        assertEquals("new-jar", Files.readString(share(tmp, "splice.jar")))
        assertEquals(NEW_VERSION, Files.readSymbolicLink(share(tmp, "releases/current")).toString())
    }
}
