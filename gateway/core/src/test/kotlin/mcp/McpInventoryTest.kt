// NEW: V4-146 — the census's own classification logic, isolated from the filesystem with stub
// McpSourceReaders (McpSourcesTest.kt proves the real readers against fixtures; this file proves
// the disposition rules and the row's mutation test: deleting a kind reader must REFUSE, never
// silently report a smaller clean number).
package mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.launch.DirectoryProbe
import splice.core.launch.McpDisposition
import splice.core.launch.McpGlobalPlan
import splice.core.launch.McpInventory
import splice.core.launch.McpRegistration
import splice.core.launch.McpSharing
import splice.core.launch.McpSourceKind
import splice.core.launch.McpSourceReader
import splice.core.launch.McpSourceScan
import java.nio.file.Path

class McpInventoryTest {

    private val canonical: Path = Path.of("/home/.claude.json")

    private fun entry(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun sharing() = McpSharing(
        enabled = true,
        exclude = emptySet(),
        endpointPrefix = "http://127.0.0.1:3096/mcp/",
        bearer = { "KEY" },
        isDirectory = DirectoryProbe { false },
    )

    private fun stub(kind: McpSourceKind, regs: List<McpRegistration> = emptyList()): McpSourceReader =
        McpSourceReader { McpSourceScan(kind, listOf(Path.of("/stub-root")), listOf(Path.of("/stub-file")), regs) }

    private fun readers(
        overrides: Map<McpSourceKind, McpSourceReader> = emptyMap(),
    ): Map<McpSourceKind, McpSourceReader> =
        McpSourceKind.entries.associateWith { kind -> overrides[kind] ?: stub(kind) }

    private fun inventory(
        overrides: Map<McpSourceKind, McpSourceReader> = emptyMap(),
        plan: JsonObject = entry("{}"),
        readerMap: Map<McpSourceKind, McpSourceReader> = readers(overrides),
    ) = McpInventory(readerMap, canonical, McpGlobalPlan { sharing().plan(plan) })

    @Test
    fun `deleting one kind reader makes the census refuse rather than under-report`() {
        val incomplete = readers().toMutableMap()
        incomplete.remove(McpSourceKind.PLUGIN_INLINE)
        val ex = assertThrows(IllegalStateException::class.java) {
            McpInventory(incomplete, canonical, McpGlobalPlan { sharing().plan(entry("{}")) })
        }
        assertTrue(ex.message!!.contains("PLUGIN_INLINE"), ex.message)
    }

    @Test
    fun `a full reader map builds without complaint`() {
        inventory() // must not throw
    }

    @Test
    fun `a GLOBAL server on the canonical home is migrated when McpSharing would host it`() {
        val reg = McpRegistration(McpSourceKind.GLOBAL, "exa", canonical, null, entry("""{"command":"npx"}"""))
        val overrides = mapOf(McpSourceKind.GLOBAL to stub(McpSourceKind.GLOBAL, listOf(reg)))
        val inv = inventory(overrides, entry("""{"exa":{"command":"npx"}}"""))
        val d = inv.census().dispositioned.single { it.registration.name == "exa" }
        assertEquals(McpDisposition.MIGRATED, d.disposition)
    }

    @Test
    fun `a GLOBAL server McpSharing rejects is excluded with McpSharing's own reason, not a made-up one`() {
        val entryJson = entry("""{"command":"node","cwd":"/x"}""")
        val reg = McpRegistration(McpSourceKind.GLOBAL, "scoped", canonical, null, entryJson)
        val plan = entry("""{"scoped":{"command":"node","cwd":"/x"}}""")
        val overrides = mapOf(McpSourceKind.GLOBAL to stub(McpSourceKind.GLOBAL, listOf(reg)))
        val inv = inventory(overrides, plan)
        val d = inv.census().dispositioned.single()
        assertEquals(McpDisposition.EXCLUDED, d.disposition)
        assertTrue(d.reason.contains("cwd"), d.reason)
    }

    @Test
    fun `a GLOBAL server declared in a different Claude identity is excluded, never migrated`() {
        val otherHome = Path.of("/home/.claude-work/.claude.json")
        val reg = McpRegistration(McpSourceKind.GLOBAL, "work-only", otherHome, null, entry("""{"command":"npx"}"""))
        val inv = inventory(mapOf(McpSourceKind.GLOBAL to stub(McpSourceKind.GLOBAL, listOf(reg))))
        val d = inv.census().dispositioned.single()
        assertEquals(McpDisposition.EXCLUDED, d.disposition)
        assertTrue(d.reason.contains("different Claude Code identity"), d.reason)
    }

    @Test
    fun `PROJECT and REPO are pending, PLUGIN kinds are excluded — every kind carries a written reason`() {
        fun reg(kind: McpSourceKind) = McpRegistration(kind, "x", Path.of("/f"), null, entry("{}"))
        fun single(kind: McpSourceKind) = stub(kind, listOf(reg(kind)))
        val overrides = mapOf(
            McpSourceKind.PROJECT to single(McpSourceKind.PROJECT),
            McpSourceKind.REPO to single(McpSourceKind.REPO),
            McpSourceKind.PLUGIN_MCP_JSON to single(McpSourceKind.PLUGIN_MCP_JSON),
            McpSourceKind.PLUGIN_INLINE to single(McpSourceKind.PLUGIN_INLINE),
        )
        val byKind = inventory(overrides).census().dispositioned.associateBy { it.registration.kind }
        assertEquals(McpDisposition.PENDING, byKind.getValue(McpSourceKind.PROJECT).disposition)
        assertEquals(McpDisposition.PENDING, byKind.getValue(McpSourceKind.REPO).disposition)
        assertEquals(McpDisposition.EXCLUDED, byKind.getValue(McpSourceKind.PLUGIN_MCP_JSON).disposition)
        assertEquals(McpDisposition.EXCLUDED, byKind.getValue(McpSourceKind.PLUGIN_INLINE).disposition)
        byKind.values.forEach { assertTrue(it.reason.isNotBlank(), "${it.registration.kind} has no written reason") }
    }

    @Test
    fun `the census reports all five kinds even when several are empty`() {
        val report = inventory().census()
        assertEquals(McpSourceKind.entries.toSet(), report.kinds.map { it.kind }.toSet())
        assertTrue(report.kinds.all { it.rootsScanned > 0 }, "every stub kind was given a root; none should read 0")
    }
}
