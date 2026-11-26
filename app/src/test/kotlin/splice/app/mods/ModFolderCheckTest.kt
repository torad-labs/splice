// NEW: what a person is told when a folder is not a mod, every fault at once and each by name.
package splice.app.mods

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ModFolderCheckTest {
    @Test
    fun `a whole mod reads with no finding and what it declares`(@TempDir dir: Path) {
        ModFixture(dir).whole()
        val read = ModFolderCheck(dir).check()
        assertEquals(emptyList<String>(), read.faults)
        val manifest = checkNotNull(read.manifest)
        assertEquals("board", manifest.name)
        assertEquals(listOf("status"), manifest.declared.tools.map { it.name })
        assertEquals(listOf("board"), manifest.declared.pages.map { it.name })
        assertEquals(listOf("refresh"), manifest.declared.commands.map { it.name })
        assertEquals(listOf("ui://board/card"), manifest.declared.views.map { it.uri })
    }

    @Test
    fun `a folder with no manifest is not a mod`(@TempDir dir: Path) {
        assertEquals(
            listOf("there is no .splice-mod/mod.json in it, so it is not a mod"),
            ModFolderCheck(dir).check().faults,
        )
    }

    @Test
    fun `a manifest that is not a JSON object is refused`(@TempDir dir: Path) {
        ModFixture(dir).write(MANIFEST, "[1, 2]")
        assertEquals(listOf(".splice-mod/mod.json is not a JSON object"), ModFolderCheck(dir).check().faults)
        ModFixture(dir).write(MANIFEST, "{ not json")
        assertEquals(listOf(".splice-mod/mod.json is not a JSON object"), ModFolderCheck(dir).check().faults)
    }

    @Test
    fun `every missing file and field is named at once`(@TempDir dir: Path) {
        ModFixture(dir).write(
            MANIFEST,
            """
            {
              "name": "Bad-Name",
              "capabilities": ["watch-sessions", "read-minds"],
              "tools": ["status", "gone"],
              "pages": [{ "title": "No name" }],
              "commands": [{ "description": "No name either" }],
              "views": [{ "uri": "ui://bad/card", "name": "Card", "file": "views/card.html" }],
              "colour": "red"
            }
            """.trimIndent(),
        )
        ModFixture(dir).write("tools/status.json", """{ "type": "object" }""")
        assertEquals(
            listOf(
                "mod.json has a key colour it does not take",
                "mod.json's name Bad-Name is not [a-z][a-z0-9]*, at most 40 characters",
                "mod.json has no version",
                "mod.json has no description",
                "mod.json's capabilities has read-minds, which is none of add-rows, answer-tools, change-launch, " +
                    "change-requests, draw-ui, run-commands, serve-http, watch-sessions, watch-turns",
                "tools lists status, and tools/status.md is missing",
                "tools lists gone, and tools/gone.json is missing",
                "tools lists gone, and tools/gone.md is missing",
                "pages entry 1 has no name",
                "commands entry 1 has no name",
                "views entry 1 names file views/card.html, which is missing",
            ),
            ModFolderCheck(dir).check().faults,
        )
    }

    @Test
    fun `a tool schema that is not an object schema and a hooks module that is absent are refused`(@TempDir dir: Path) {
        val fixture = ModFixture(dir)
        fixture.whole()
        fixture.write("tools/status.json", """{ "type": "string" }""")
        fixture.write("hooks/hooks.json", """{ "modules": ["./register.ts", "./missing.ts"] }""")
        assertEquals(
            listOf(
                "tools/status.json is not a JSON Schema object with type object",
                "hooks/hooks.json lists module ./missing.ts, and hooks/missing.ts is missing",
            ),
            ModFolderCheck(dir).check().faults,
        )
    }

    @Test
    fun `a view file outside the folder is refused`(@TempDir dir: Path) {
        val fixture = ModFixture(dir)
        fixture.whole()
        fixture.manifest(views = """[{ "uri": "ui://board/card", "name": "Card", "file": "../secret.html" }]""")
        assertEquals(
            listOf("views entry 1's file ../secret.html is outside the mod's folder"),
            ModFolderCheck(dir).check().faults,
        )
    }
}

/** A small mod written into a test's folder. */
internal class ModFixture(private val root: Path) {
    fun write(relative: String, text: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    fun manifest(
        name: String = "board",
        views: String = """[{ "uri": "ui://board/card", "name": "Card", "file": "views/card.html" }]""",
    ) {
        write(
            MANIFEST,
            """
            {
              "name": "$name",
              "version": "0.1.0",
              "description": "Shows a board of the work in flight.",
              "author": "A Person",
              "capabilities": ["watch-sessions", "watch-turns"],
              "tools": ["status"],
              "pages": [{ "name": "board", "title": "Board" }],
              "commands": [{ "name": "refresh", "description": "Read the board again." }],
              "views": $views
            }
            """.trimIndent(),
        )
    }

    fun whole(name: String = "board") {
        manifest(name)
        write("tools/status.json", """{ "type": "object", "properties": {} }""")
        write("tools/status.md", "Says how the board stands.")
        write("views/card.html", "<p>card</p>")
        write("hooks/hooks.json", """{ "modules": ["./register.ts"] }""")
        write("hooks/register.ts", "export const register = on => {}\n")
    }
}
