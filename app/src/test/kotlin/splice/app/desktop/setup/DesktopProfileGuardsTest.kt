package splice.app.desktop.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class DesktopProfileGuardsTest {
    @TempDir
    lateinit var tmp: Path

    private val env = EnvReader { key ->
        when (key) {
            "XDG_CONFIG_HOME" -> tmp.resolve("config").toString()
            "HOME" -> tmp.toString()
            "SPLICE_STATE_DIR" -> tmp.resolve("state").toString()
            else -> null
        }
    }
    private val program get() = DesktopPrograms(tmp.resolve("state"))
    private val wanted get() = DesktopMcpWanted(bridged = listOf("hosted"), program = program)

    @Test
    fun `a claude_desktop_config json that holds no JSON object is refused, never written over`() {
        val target = Files.createDirectories(tmp.resolve("Claude-3p"))
        val config = target.resolve("claude_desktop_config.json")
        Files.writeString(config, "{ \"mcpServers\": { broken")
        val sync = DesktopProfileSync(env, "Linux", tmp, mcp = { wanted })

        assertThrows(IOException::class.java) { sync.syncConfigInto(target) }
        assertEquals("{ \"mcpServers\": { broken", Files.readString(config), "the person's file is left as it is")
    }

    @Test
    fun `a claude_desktop_config json that is a link is refused and what it points at is untouched`() {
        val target = Files.createDirectories(tmp.resolve("Claude-3p"))
        val elsewhere = Files.writeString(tmp.resolve("elsewhere.json"), "{}")
        Files.createSymbolicLink(target.resolve("claude_desktop_config.json"), elsewhere)
        val sync = DesktopProfileSync(env, "Linux", tmp, mcp = { wanted })

        assertThrows(IOException::class.java) { sync.syncConfigInto(target) }
        assertEquals("{}", Files.readString(elsewhere))
        assertTrue(Files.isSymbolicLink(target.resolve("claude_desktop_config.json")))
    }

    @Test
    fun `a malformed extensions registry is refused and a registry id that climbs out is never listed`() {
        val source = Files.createDirectories(tmp.resolve("config/Claude"))
        Files.createDirectories(source.resolve("Claude Extensions/ext.one"))
        Files.writeString(source.resolve("Claude Extensions/ext.one/manifest.json"), "{}")
        Files.writeString(
            source.resolve("extensions-installations.json"),
            """{"extensions":{"ext.one":{},"../outside":{}}}""",
        )
        val target = Files.createDirectories(tmp.resolve("config/claude-splice/Claude-3p"))
        Files.createDirectories(target.resolve("outside"))
        val sync = DesktopProfileSync(env, "Linux", tmp, mcp = { DesktopMcpWanted() })

        assertTrue(sync.syncInto(target))
        val listed = Json.parseToJsonElement(Files.readString(target.resolve("extensions-installations.json")))
            .jsonObject.getValue("extensions").jsonObject.keys
        assertEquals(setOf("ext.one"), listed, "an id that names a folder outside the extensions is not listed")

        Files.writeString(target.resolve("extensions-installations.json"), "not json")
        Files.createDirectories(source.resolve("Claude Extensions/ext.two"))
        assertThrows(IOException::class.java) { sync.syncInto(target) }
        assertEquals("not json", Files.readString(target.resolve("extensions-installations.json")))
    }

    @Test
    fun `a read-only folder is copied read-only and a link that climbs out as written is dropped`() {
        val ext = Files.createDirectories(tmp.resolve("src/ext"))
        val locked = Files.createDirectories(ext.resolve("locked"))
        Files.writeString(locked.resolve("data.txt"), "kept")
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"))
        Files.createSymbolicLink(ext.resolve("loop"), Path.of("../ext/locked/data.txt"))
        val into = tmp.resolve("dst/ext")

        assertTrue(DesktopTreeCopy().copy(ext, into))
        assertEquals("kept", Files.readString(into.resolve("locked/data.txt")))
        assertEquals("r-xr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(into.resolve("locked"))))
        assertFalse(Files.exists(into.resolve("loop"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(DesktopTreeCopy().copy(ext, into), "a folder already there is never copied over")
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.setPosixFilePermissions(into.resolve("locked"), PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    @Test
    fun `a folder too large to copy whole is not copied and leaves no staging folder behind`() {
        val ext = Files.createDirectories(tmp.resolve("src/big"))
        repeat(FILES) { Files.writeString(ext.resolve("f$it.txt"), "x") }
        val locked = Files.createDirectories(ext.resolve("locked"))
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"))
        val parent = Files.createDirectories(tmp.resolve("dst"))

        assertFalse(DesktopTreeCopy(maxEntries = FILES - 1).copy(ext, parent.resolve("big")))
        assertEquals(emptyList<Path>(), Files.list(parent).use { it.toList() })
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    @Test
    fun `the bridge program is laid again when its mode is not owner-only`() {
        val target = Files.createDirectories(tmp.resolve("Claude-3p"))
        val sync = DesktopProfileSync(env, "Linux", tmp, mcp = { wanted })
        assertTrue(sync.syncConfigInto(target))
        Files.setPosixFilePermissions(program.mcp, PosixFilePermissions.fromString("rwxr-xr-x"))

        sync.syncConfigInto(target)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(program.mcp)))
    }
}

private const val FILES = 4
