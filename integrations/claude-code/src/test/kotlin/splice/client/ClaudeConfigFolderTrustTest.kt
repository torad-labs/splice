// NEW: V4-283 — a folder the operator trusted in Claude Code is trusted in every head. claudex -r of a
// claude-splice session in /tmp/tally-resume-3 met Claude Code's "Quick safety check" at +121 s
// (take-resume-3, 2026-09-26): each head keeps its own .claude.json, and the trust record lived only in
// claude-splice's. A launch now carries the RECORDS the operator already granted (their ~/.claude.json
// and every other head's) for the launch cwd and its ancestors, and Claude Code's own walk decides
// from there. Every home here is a fixture; nothing reads or writes the operator's real files.
package splice.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.wrap.WrappedLaunch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val ACCEPTED = "hasTrustDialogAccepted"

class ClaudeConfigFolderTrustTest {
    @TempDir
    lateinit var home: Path

    private val spliceHead by lazy { home.resolve(".claude-claude-splice") }
    private val claudex by lazy { home.resolve(".claude-claudex") }

    /** A real folder under the fixture home, spelled as Claude Code keys it: its real path. */
    private fun folder(rel: String): Path = Files.createDirectories(home.resolve(rel)).toRealPath()

    /** A state file whose `projects` records each path with its trust. */
    private fun records(file: Path, vararg entries: Pair<String, Boolean>) {
        Files.createDirectories(file.parent)
        val state = buildJsonObject {
            putJsonObject("projects") {
                entries.forEach { (path, trust) -> putJsonObject(path) { put(ACCEPTED, trust) } }
            }
        }
        file.writeText(state.toString())
    }

    private fun spec(configDir: Path) = MaterializeSpec(
        configDir,
        ClaudePolicy(share = emptySet(), isolate = emptySet()),
        listOf("gpt-6-sol"),
        "gpt-6-sol",
        buildJsonObject { },
        "\"/usr/bin/curl\" -s :3096/statusline",
    )

    /** claudex launched at [cwd], with claude-splice as the other head, and the state file it gets. */
    private fun launchClaudex(cwd: Path): JsonObject {
        ClaudeConfigMaterializer(home).materialize(spec(claudex), TrustedLaunch(cwd, listOf(spliceHead)))
        return Json.parseToJsonElement(claudex.resolve(".claude.json").readText()).jsonObject
    }

    private fun trust(state: JsonObject, path: String): Boolean? =
        ((state["projects"] as? JsonObject)?.get(path) as? JsonObject)?.get(ACCEPTED)?.jsonPrimitive?.booleanOrNull

    @Test
    fun `a folder trusted only in another head's state file is trusted in this head`() {
        val take = folder("tally-resume-3")
        records(spliceHead.resolve(".claude.json"), take.toString() to true)

        val state = launchClaudex(take)
        assertEquals(true, trust(state, take.toString()), "claude-splice's record is carried: $state")
    }

    @Test
    fun `a folder trusted only in the operator's own state file is trusted in this head`() {
        val take = folder("tally-resume-4")
        records(home.resolve(".claude.json"), take.toString() to true)

        val state = launchClaudex(take)
        assertEquals(true, trust(state, take.toString()), "the operator's record is carried: $state")
    }

    @Test
    fun `a folder under a trusted folder carries that folder's record, never a verdict for the cwd`() {
        val project = folder("notes")
        val deep = folder("notes/drafts/2026")
        records(spliceHead.resolve(".claude.json"), project.toString() to true)

        val state = launchClaudex(deep)
        assertEquals(true, trust(state, project.toString()), "the ancestor's record is carried: $state")
        assertNull(trust(state, deep.toString()), "the cwd gets no record of its own; Claude Code's walk decides")
    }

    @Test
    fun `a child or sibling of the cwd, an untrusted folder, home and the root are never carried`() {
        val take = folder("work/take")
        val child = folder("work/take/child")
        val sibling = folder("work/other")
        records(
            spliceHead.resolve(".claude.json"),
            child.toString() to true,
            sibling.toString() to true,
            home.toRealPath().toString() to true,
            "/" to true,
        )
        records(home.resolve(".claude.json"), take.toString() to false)

        val state = launchClaudex(take)
        for (path in listOf(child, sibling, home.toRealPath(), take).map(Path::toString) + "/") {
            assertNotEquals(true, trust(state, path), "$path is not carried: $state")
        }
    }

    @Test
    fun `a head entry holding the default false is seeded, and every other key survives`() {
        val take = folder("tally-resume-5")
        val other = folder("elsewhere")
        records(spliceHead.resolve(".claude.json"), take.toString() to true)
        Files.createDirectories(claudex)
        claudex.resolve(".claude.json").writeText(
            """{"numStartups":7,"projects":{"$take":{"allowedTools":["Bash"],"$ACCEPTED":false},""" +
                """"$other":{"$ACCEPTED":true,"lastCost":1.5}}}""",
        )

        val state = launchClaudex(take)
        val entry = state["projects"]!!.jsonObject[take.toString()]!!.jsonObject
        assertEquals(true, entry[ACCEPTED]?.jsonPrimitive?.booleanOrNull, "false is Claude Code's default: $state")
        assertEquals("Bash", entry["allowedTools"]!!.jsonArray.single().jsonPrimitive.content, "the entry's keys stay")
        assertEquals("1.5", state["projects"]!!.jsonObject[other.toString()]!!.jsonObject["lastCost"].toString())
        assertEquals("7", state["numStartups"].toString(), "every other key survives")
    }

    @Test
    fun `a cwd reached through a symlink carries its real folder's record`() {
        val take = folder("tally-resume-6")
        val link = Files.createSymbolicLink(home.resolve("take-link"), take)
        records(spliceHead.resolve(".claude.json"), take.toString() to true)

        val state = launchClaudex(link)
        assertEquals(true, trust(state, take.toString()), "Claude Code keys the real path: $state")
    }

    @Test
    fun `the wrapped default head carries the same records`() {
        val take = folder("tally-resume-7")
        records(spliceHead.resolve(".claude.json"), take.toString() to true)
        val vanilla = home.resolve(".claude")

        val wrap = WrappedLaunch(vanilla, ClaudeConfigMaterializer(home))
        wrap.materialize(spec(vanilla), TrustedLaunch(take, listOf(spliceHead)))
        val state = Json.parseToJsonElement(vanilla.resolve(".claude.json").readText()).jsonObject
        assertTrue(trust(state, take.toString()) == true, "the wrap door seeds too: $state")
    }
}
