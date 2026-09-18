// NEW: V4-115 cross-head resume as an explicit COPY (2026-09-17). The shared projects tree is gone
// (three heads linked into the operator's vanilla ~/.claude/projects and 95 head transcripts landed
// there), so the only way a session started on one head can be resumed on another is a copy made at
// launch time, on demand, for one named id. These tests pin the properties that make that copy safe:
// the SOURCE is never written, nothing lands as a link, only the calling head's own tree is searched
// for a local hit, and the copy's assistant models follow the head that is resuming them — which is
// what stops Claude Code's "Session model X could not be restored".
package splice.core.launch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonScalars
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink

class ResumeAcrossHeadsTest {

    private val pinned = "head-model"

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        return Files.writeString(path, text)
    }

    /** Claude Code's projects/ subdir name: the absolute cwd with every non-alphanumeric run to `-`. */
    private fun encodedCwd(name: String): String = "-home-operator-$name"

    private fun headConfig(home: Path, key: String): Path = Files.createDirectories(home.resolve(".claude-$key"))

    /** user row, assistant row on [model], and a row that HAS a message but is not an assistant row. */
    private fun transcript(sessionId: String, model: String): String =
        """{"type":"user","sessionId":"$sessionId","message":{"role":"user","content":"hi"}}
{"type":"assistant","sessionId":"$sessionId","message":{"id":"m1","model":"$model","content":[]}}
{"type":"summary","sessionId":"$sessionId","message":{"model":"$model"}}
"""

    private fun register(home: Path, key: String, cwdDir: String, sessionId: String, model: String): Path =
        write(
            headConfig(home, key).resolve(Keys.PROJECTS).resolve(cwdDir).resolve("$sessionId.jsonl"),
            transcript(sessionId, model),
        )

    private fun lines(text: String): List<String> = text.split("\n")

    private fun modelOf(row: String): String? =
        JsonScalars.str(Json.parseToJsonElement(row).jsonObject["message"] as? JsonObject, Keys.MODEL)

    private fun adoption(calling: Path, others: List<Path>, sessionId: String): SessionAdoption =
        ResumeAcrossHeads().adopt(calling, others, sessionId, pinned, log = {})

    @Test
    fun `a foreign session is copied in, its models follow the head, and the source is untouched`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        val source = register(home, "kimi", encodedCwd("repo"), "abc-123", model = "k3-256k")
        val sourceText = Files.readString(source)
        val copy = calling.resolve(Keys.PROJECTS).resolve(encodedCwd("repo")).resolve("abc-123.jsonl")
        assertFalse(Files.exists(copy, NOFOLLOW_LINKS), "the picker input is this head's tree only")

        val adopted = adoption(calling, listOf(headConfig(home, "kimi")), "abc-123") as SessionAdoption.Adopted

        assertEquals(1, adopted.modelsRewritten, "exactly the assistant row carries a model")
        assertEquals(copy, adopted.into, "the copy lands in THIS head's tree, under the source's encoded cwd")
        assertEquals(source, adopted.from)
        assertFalse(copy.isSymbolicLink(), "the adoption must be a real file, never a link")
        assertEquals(1, Files.getAttribute(copy, "unix:nlink") as Int, "no hardlink back to the source")
        assertEquals(pinned, modelOf(lines(Files.readString(copy))[1]), "the assistant model follows the resuming head")
        assertEquals(sourceText, Files.readString(source), "the source transcript must be byte-identical")
        assertNotEquals(sourceText, Files.readString(copy), "the copy is rewritten, so it is not the same bytes")
    }

    @Test
    fun `rows that need no rewrite are copied byte-identical`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        val source = register(home, "kimi", encodedCwd("repo"), "abc-123", model = pinned)
        val before = lines(Files.readString(source))

        val adopted = adoption(calling, listOf(headConfig(home, "kimi")), "abc-123") as SessionAdoption.Adopted

        assertEquals(0, adopted.modelsRewritten, "the assistant row already names this head's model")
        val after = lines(Files.readString(adopted.into))
        assertEquals(before[0], after[0], "a user row is never re-encoded")
        assertEquals(before[1], after[1], "an assistant row on the right model is never re-encoded")
        assertEquals(before[2], after[2], "a non-assistant row with a message is not an assistant message")
    }

    @Test
    fun `the calling head's own session wins, and the other head is never rewritten`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        val mine = register(home, "codex", encodedCwd("repo"), "abc-123", model = pinned)
        val theirs = register(home, "kimi", encodedCwd("repo"), "abc-123", model = "k3-256k")
        val theirsText = Files.readString(theirs)

        val adoption = adoption(calling, listOf(headConfig(home, "kimi")), "abc-123")

        assertEquals(mine, (adoption as SessionAdoption.HeadOwned).transcript)
        assertEquals(theirsText, Files.readString(theirs), "an unadopted head's transcript is not touched")
    }

    @Test
    fun `same encoded cwd is preferred over any other`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        // this head knows the repo cwd: it holds a tree for it, just not this session
        write(calling.resolve(Keys.PROJECTS).resolve(encodedCwd("repo")).resolve("unrelated.jsonl"), "{}\n")
        register(home, "kimi", encodedCwd("elsewhere"), "abc-123", model = "k3-256k")
        val wanted = register(home, "kimi", encodedCwd("repo"), "abc-123", model = "k3-256k")

        val adopted = adoption(calling, listOf(headConfig(home, "kimi")), "abc-123") as SessionAdoption.Adopted

        assertEquals(encodedCwd("repo"), adopted.into.parent.fileName.toString(), "the cwd this head knows wins")
        assertEquals(wanted, adopted.from)
    }

    @Test
    fun `the session subdir is copied, and a link inside it is copied as content`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        val source = register(home, "kimi", encodedCwd("repo"), "abc-123", model = "k3-256k")
        val subdir = source.parent.resolve("abc-123")
        write(subdir.resolve("subagents/agent-1.jsonl"), transcript("abc-123", "k3-256k"))
        // Claude Code writes subagent transcripts as absolute symlinks; a verbatim copy would point
        // back into the source head's tree and re-open the leak this row removes.
        Files.createSymbolicLink(subdir.resolve("tool-results.jsonl"), source)

        val adopted = adoption(calling, listOf(headConfig(home, "kimi")), "abc-123") as SessionAdoption.Adopted

        val copiedSubdir = adopted.into.parent.resolve("abc-123")
        assertTrue(Files.isRegularFile(copiedSubdir.resolve("subagents/agent-1.jsonl")), "the subdir tree is copied")
        val fromLink = copiedSubdir.resolve("tool-results.jsonl")
        assertFalse(fromLink.isSymbolicLink(), "a link is copied as CONTENT, never as a link")
        assertTrue(Files.isRegularFile(fromLink, NOFOLLOW_LINKS))
        assertEquals(pinned, modelOf(lines(Files.readString(fromLink))[1]), "the linked transcript is rewritten too")
    }

    @Test
    fun `an id in no head's tree is Absent and nothing is written`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")
        val other = headConfig(home, "kimi")
        register(home, "kimi", encodedCwd("repo"), "other-session", model = "k3-256k")

        val adoption = adoption(calling, listOf(other), "abc-123")

        assertEquals("abc-123", (adoption as SessionAdoption.Absent).sessionId)
        assertFalse(
            Files.exists(calling.resolve(Keys.PROJECTS), NOFOLLOW_LINKS),
            "a resume that found nothing must not create a tree or a file",
        )
    }

    @Test
    fun `an argument that is not a session id is refused without being echoed`(@TempDir home: Path) {
        val calling = headConfig(home, "codex")

        val adoption = adoption(calling, listOf(headConfig(home, "kimi")), "../../etc/passwd")

        assertTrue(adoption is SessionAdoption.Invalid)
        assertFalse(adoption.toString().contains("passwd"), "the rejected argument is not reproduced: $adoption")
        assertFalse(Files.exists(calling.resolve(Keys.PROJECTS), NOFOLLOW_LINKS))
    }
}
