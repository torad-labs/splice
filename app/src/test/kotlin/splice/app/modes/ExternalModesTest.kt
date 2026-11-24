package splice.app.modes

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.app.DaemonProcess
import splice.app.cli.CONFIG_REFUSED_EXIT
import splice.app.cli.ConfigPreflight
import splice.core.server.ModAnswer
import splice.core.server.ModCaller
import splice.core.server.ModRequest
import splice.core.server.ModResponse
import splice.core.util.EnvReader
import splice.core.util.TopologyRefusal
import splice.modes.api.Mode
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class ExternalModesTest {
    @Test
    fun `a pinned jar can serve alongside the shipped catalog with custom Desktop windows`(@TempDir root: Path) {
        val fixture = ModFixture(root)
        val bytes = modJar()
        fixture.store("demo.jar", bytes)
        fixture.config(
            modRow("Demo release", "path", "demo.jar", pin(bytes).uppercase()) +
                """
                [modes.demo]
                on = true
                server_routes = true
                [desktop]
                code_window = 320000
                """.trimIndent(),
        )

        assertEquals(0, fixture.preflight().first)
        val start = DaemonProcess().prepare(fixture.env)
        val topology = start.loaded.topology
        assertEquals(320_000L, topology.desktop.codeWindow)
        assertNotNull(start.boot.modes.catalog.mode("template"))
        val modes = DaemonModes(start.boot.modes.catalog, topology.modes, topology.heads.keys.toList(), fixture.state)
        val caller = ModCaller("ada@team.example", "core", false)
        val request = ModRequest("GET", listOf("demo"), caller, emptyMap(), emptyMap(), ByteArray(0))
        val answer = assertInstanceOf(ModAnswer.Answered::class.java, ModeServerRoutes(modes).answer("demo", request))
        assertEquals("from the pinned archive", answer.response?.text())
    }

    @Test
    fun `a tampered file refuses boot with the same pin finding as preflight`(@TempDir root: Path) {
        val fixture = ModFixture(root)
        fixture.store("demo.jar", modJar(message = "changed"))
        fixture.config(modRow("demo", "path", "demo.jar", pin(modJar())))
        fixture.assertRefuses("mods.jar[1].sha256", "SHA-256 does not match")
    }

    @Test
    fun `matching a pin is not enough for a truncated jar or a missing file`(@TempDir root: Path) {
        val fixture = ModFixture(root)
        val bytes = modJar().dropLast(22).toByteArray()
        fixture.store("broken.jar", bytes)
        fixture.config(
            modRow("broken", "path", "broken.jar", pin(bytes)) + modRow("absent", "path", "absent.jar", pin(bytes)),
        )
        fixture.assertRefuses("mods.jar[1].path", "valid JAR", "mods.jar[2].path", "does not exist")
    }

    @Test
    fun `a world writable mods directory refuses a matching file`(@TempDir root: Path) {
        val fixture = ModFixture(root)
        val bytes = modJar()
        val path = fixture.store("demo.jar", bytes)
        Files.setPosixFilePermissions(path.parent, PosixFilePermissions.fromString("rwxrwxrwx"))
        fixture.config(modRow("demo", "path", "demo.jar", pin(bytes)))

        try {
            fixture.assertRefuses("mods.dir", "world-writable")
        } finally {
            Files.setPosixFilePermissions(path.parent, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `a symlink cannot point the mod directory or jar outside its directory`(@TempDir root: Path) {
        val fixture = ModFixture(root)
        val bytes = modJar()
        val outside = Files.write(root.resolve("outside.jar"), bytes)
        val path = fixture.store("demo.jar", bytes)
        Files.createSymbolicLink(path.resolveSibling("escape.jar"), outside)
        Files.createSymbolicLink(root.resolve("alias"), path.parent)
        val row = modRow("demo", "path", "escape.jar", pin(bytes))
        fixture.config(row)
        fixture.assertRefuses("mods.jar[1].path", "symlink escapes")
        fixture.config("[mods]\ndir = 'alias'\n" + row)
        fixture.assertRefuses("mods.dir", "symbolic link")
    }

    @Test
    fun `malformed and missing pins duplicate names and ambiguous sources are listed together`(@TempDir root: Path) {
        val fixture = ModFixture(root)
        fixture.config(
            """
            [[mods.jar]]
            name = "demo"
            path = "demo.jar"
            url = "https://example.invalid/secret"
            sha256 = "not-a-pin"
            [[mods.jar]]
            name = "demo"
            """.trimIndent(),
            daemon = "control_port = 'not-a-port'",
        )

        fixture.assertRefuses(
            "daemon.control_port",
            "mods.jar[1].sha256",
            "mods.jar[2].sha256",
            "mods.jar[2].name",
            "duplicate mod name",
            "exactly one of path or url",
        )
        assertFalse(fixture.preflight().second.contains("https://example.invalid/secret"))
    }

    @Test
    fun `boot reports duplicate mode names, reserved routes and invalid Desktop windows together`(@TempDir root: Path) {
        val fixture = ModFixture(root)
        val shipped = modJar(name = "template")
        val first = modJar()
        val second = modJar(message = "another archive")
        val reserved = modJar(name = "door")
        val path = fixture.store("shipped.jar", shipped)
        fixture.store("one.jar", first)
        fixture.store("two.jar", second)
        fixture.store("door.jar", reserved)
        fixture.config(
            modRow("release A", "path", path.toString(), pin(shipped)) +
                modRow("release B", "path", "one.jar", pin(first)) +
                modRow("release C", "path", "two.jar", pin(second)) +
                modRow("release D", "path", "door.jar", pin(reserved)) +
                "[modes.door]\nserver_routes = true\n[desktop]\ncode_window = 0",
        )
        fixture.assertRefuses(
            "mods.jar[1]",
            "mode name 'template' is already provided",
            "mods.jar[3]",
            "mode name 'demo' is already provided",
            "modes.door.server_routes",
            "the server's listener already answers that name",
            "desktop.code_window",
        )
    }

    @Test
    fun `a cold URL is checked without writes then boot caches only the pinned archive`(@TempDir root: Path) {
        val fixture = ModFixture(root)
        val bytes = modJar()
        val wrongPin = pin(modJar(message = "different release"))
        val cache = fixture.state.resolve("mod-${pin(bytes)}.jar")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/demo.jar") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/demo.jar"
        try {
            fixture.config(modRow("demo", "url", url, wrongPin))
            fixture.assertRefuses("mods.jar[1].sha256", "SHA-256 does not match")
            assertFalse(Files.exists(fixture.state))
            fixture.config(modRow("demo", "url", url, pin(bytes)))
            assertEquals(0, fixture.preflight().first)
            assertFalse(Files.exists(fixture.state))
            val start = DaemonProcess().prepare(fixture.env)
            assertNotNull(start.boot.modes.catalog.mode("demo"))
            assertArrayEquals(bytes, Files.readAllBytes(cache))
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(cache)))
        } finally {
            server.stop(0)
        }

        assertEquals(0, fixture.preflight().first, "a matching cache works without the download server")
        assertNotNull(DaemonProcess().prepare(fixture.env).boot.modes.catalog.mode("demo"))
        Files.write(cache, modJar(message = "tampered cache"))
        fixture.assertRefuses("mods.jar[1].sha256", "SHA-256 does not match")
    }
}

private class ModFixture(private val root: Path) {
    val state: Path = root.resolve("state")
    private val config = root.resolve("splice.toml")
    val env = EnvReader {
        when (it) {
            "SPLICE_CONFIG" -> config.toString()
            "HOME" -> root.toString()
            else -> null
        }
    }

    fun config(text: String, daemon: String = "") {
        Files.writeString(config, "[daemon]\nstate_dir = '$state'\n$daemon\n$text\n")
    }

    fun store(file: String, bytes: ByteArray): Path =
        Files.write(Files.createDirectories(root.resolve("mods")).resolve(file), bytes)

    fun preflight(): Pair<Int, String> {
        val lines = mutableListOf<String>()
        return ConfigPreflight(env) { lines += it }.check() to lines.joinToString("\n")
    }

    fun assertRefuses(vararg expected: String) {
        val boot = assertThrows<TopologyRefusal> { DaemonProcess().prepare(env) }
        val (code, output) = preflight()
        assertEquals(CONFIG_REFUSED_EXIT, code, output)
        expected.forEach {
            assertTrue(it in boot.message.orEmpty(), boot.message)
            assertTrue(it in output, output)
        }
    }
}

private fun modRow(name: String, source: String, location: String, pin: String) = """
    [[mods.jar]]
    name = "$name"
    $source = "$location"
    sha256 = "$pin"
""".trimIndent() + "\n"

private fun pin(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun modJar(name: String = "demo", message: String = "from the pinned archive"): ByteArray {
    val type = PinnedDemoMode::class.java
    val classEntry = type.name.replace('.', '/') + ".class"
    val classBytes = requireNotNull(type.classLoader.getResourceAsStream(classEntry)).use { it.readBytes() }
    val bytes = ByteArrayOutputStream()
    JarOutputStream(bytes).use { jar ->
        jar.put("splice-modes/index", "$name\n".toByteArray())
        jar.put("META-INF/services/splice.modes.api.Mode", "${type.name}\n".toByteArray())
        jar.put(
            "splice-modes/$name/mode.toml",
            """
            name = "$name"
            summary = "Pinned mod fixture"
            server = "ext_$name"
            handler = "${type.name}"
            tools = []
            """.trimIndent().toByteArray(),
        )
        jar.put(classEntry, classBytes)
        jar.put("pinned-demo.txt", message.toByteArray())
    }
    return bytes.toByteArray()
}

private fun JarOutputStream.put(name: String, bytes: ByteArray) {
    putNextEntry(JarEntry(name).apply { time = 0 })
    write(bytes)
    closeEntry()
}

class PinnedDemoMode : Mode {
    override fun answer(call: ModeCall): ModeAnswer = ModeAnswer.Text("pinned demo")

    override fun resource(uri: String): String? =
        javaClass.getResourceAsStream("/pinned-demo.txt")?.use { it.readBytes().decodeToString() }

    override fun serve(request: ModRequest): ModResponse? = resource("ui://demo")
        ?.takeIf { request.method == "GET" && request.path == listOf("demo") }
        ?.let { ModResponse(200, "text/plain", it.toByteArray()) }
}
