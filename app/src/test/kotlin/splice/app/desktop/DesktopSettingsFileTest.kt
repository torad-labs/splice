// The person's own settings file as splice leaves it when it takes an older splice's door out: Claude Code writes the
// same file, so a change it made while splice looked is kept, and the file is written at its own mode.
package splice.app.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class DesktopSettingsFileTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun mode(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    @Test
    fun `a file Claude Code wrote while splice looked is left as Claude Code wrote it`(@TempDir dir: Path) {
        val path = dir.resolve("settings.json")
        val read = json("""{"theme":"dark","env":{"FOO":"1"}}""")
        val written = """{"theme":"dark","model":"opus","env":{"FOO":"1"}}"""
        Files.writeString(path, written)
        val file = DesktopSettingsFile(path, dir.resolve("no-status"))
        assertFalse(file.replace(read, json("""{"theme":"dark"}""")))
        assertEquals(written, Files.readString(path))
        assertFalse(file.replace(read, null))
        assertEquals(written, Files.readString(path))
    }

    @Test
    fun `a file that still reads as it did is replaced, written as Claude Code writes it`(@TempDir dir: Path) {
        val path = dir.resolve("settings.json")
        Files.writeString(path, """{"theme":"dark","model":"claude-astra--astra-large"}""")
        val file = DesktopSettingsFile(path, dir.resolve("no-status"))
        val read = json("""{"theme":"dark","model":"claude-astra--astra-large"}""")
        assertTrue(file.replace(read, json("""{"theme":"dark"}""")))
        assertEquals("{\n  \"theme\": \"dark\"\n}\n", Files.readString(path))
        assertTrue(file.replace(json("""{"theme":"dark"}"""), null))
        assertFalse(Files.exists(path))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `the file keeps its mode, and one the older door left owner-only gets a new file's mode`(@TempDir dir: Path) {
        val path = dir.resolve("settings.json")
        val status = dir.resolve("status")
        Files.writeString(status, "Name:\tjava\nUmask:\t0022\n")
        val file = DesktopSettingsFile(path, status)
        Files.writeString(path, """{"a":1}""")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-rw-r--"))
        assertTrue(file.replace(json("""{"a":1}"""), json("""{"a":2}""")))
        assertEquals("rw-rw-r--", mode(path))
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        assertTrue(file.replace(json("""{"a":2}"""), json("""{"a":3}""")))
        assertEquals("rw-r--r--", mode(path))
    }
}
