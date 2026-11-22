// NEW: modes-mcp (Nov 21, 2026) — what a launch is handed for the MCP servers the daemon answers itself: one owner-only
// file of entries that point at the daemon, under a `--mcp-config` of its own after whatever the launch carries.
package splice.client.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class OwnMcpLaunchTest {
    @TempDir
    lateinit var dir: Path

    private val sharing =
        McpSharing(true, emptySet(), "http://127.0.0.1:3096/mcp/", { "k-mcp" }, DirectoryProbe { false })

    /** `harbor` is the daemon's for every launch on `acme`, and `quay` for a head's own launch there. */
    private fun launch() = OwnMcpLaunch(
        { head, caller ->
            if (head != "acme") {
                emptyList()
            } else {
                listOf(
                    "harbor",
                ) + listOf("quay").filter { caller == "head" }
            }
        },
        sharing,
        dir,
        LogSink { },
    )

    @Test
    fun `a launch with no MCP file of its own gets the flag and the daemon's file`() {
        val args = launch().added(listOf("--effort", "high"), "acme", "door")
        val file = dir.resolve("own-mcp-acme-door.json")
        assertEquals(listOf("--effort", "high", "--mcp-config", file.toString()), args)
        assertEquals(
            """{"mcpServers":{"harbor":{"type":"http","url":"http://127.0.0.1:3096/mcp/harbor",""" +
                """"headers":{"Authorization":"Bearer k-mcp","X-Splice-Head":"acme"}}}}""",
            Files.readString(file),
        )
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun `a launch that already carries a file gets the daemon's under a flag of its own, after the launch's`() {
        val args = launch().added(
            listOf("--mcp-config", "/s/hub-41.json", "--plugin-dir-no-mcp", "/p/one"),
            "acme",
            "head",
        )
        val file = dir.resolve("own-mcp-acme-head.json")
        assertEquals(
            listOf("--mcp-config", "/s/hub-41.json", "--plugin-dir-no-mcp", "/p/one", "--mcp-config", file.toString()),
            args,
        )
        assertEquals(
            setOf("harbor", "quay"),
            Json.parseToJsonElement(Files.readString(file)).jsonObject.getValue("mcpServers").jsonObject.keys,
        )
    }

    @Test
    fun `a launch the daemon answers no server for is left as it was, and no file is written`() {
        val args = listOf("--mcp-config", "/s/hub-41.json")
        assertEquals(args, launch().added(args, "globex", "door"))
        assertFalse(Files.list(dir).use { it.findAny().isPresent })
    }
}
