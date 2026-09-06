// NEW (2026-09-05): the activity label the proxy composes for Claude Code's 30-second side query,
// instead of a full-context upstream read every 30 s per session.
package head

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.wire.AnthropicRequest
import splice.gateway.head.ActivityLabel

class ActivityLabelTest {

    private val label = ActivityLabel()

    private fun request(vararg messages: String): AnthropicRequest =
        AnthropicParse.parseAnthropicBody("""{"model":"m","messages":[${messages.joinToString(",")}]}""").typed

    private fun toolUse(name: String, input: String) =
        """{"role":"assistant","content":[{"type":"text","text":"On it."},""" +
            """{"type":"tool_use","id":"t1","name":"$name","input":$input}]}"""

    private val toolResult = """{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}"""
    private val user = """{"role":"user","content":"fix the bug"}"""

    // The prompt as Claude Code 2.1.257 builds it (wxo): the opening sentence, the previous label,
    // its good/bad examples.
    private val query = """{"role":"user","content":[{"type":"text","text":""" +
        """"Describe your most recent action in 3-5 words using present tense (-ing). """ +
        """Name the file or function, not the branch. Do not use tools.\n\n""" +
        """Previous: \"Reading foo\" — say something NEW.\n\nGood: \"Reading runAgent.ts\""}]}"""

    private fun labelAfter(name: String, input: String) =
        label.labelFor(request(user, toolUse(name, input), toolResult, query))

    @Test
    fun `file tools name the file`() {
        assertEquals("Reading TurnStreamer.kt", labelAfter("Read", """{"file_path":"/repo/g/TurnStreamer.kt"}"""))
        assertEquals(
            "Editing HeadAdmission.kt",
            labelAfter("Edit", """{"file_path":"/r/HeadAdmission.kt","old_string":"a"}"""),
        )
        assertEquals("Writing notes.md", labelAfter("Write", """{"file_path":"notes.md","content":"x"}"""))
        assertEquals("Editing lab.ipynb", labelAfter("NotebookEdit", """{"notebook_path":"/n/lab.ipynb"}"""))
        assertEquals("Reading a file", labelAfter("Read", """{}"""))
    }

    @Test
    fun `bash names the first real command past the shell prelude and before the pipe`() {
        assertEquals("Running git status", labelAfter("Bash", """{"command":"cd /repo && git status --short"}"""))
        assertEquals(
            "Running gradlew test",
            labelAfter("Bash", """{"command":"JAVA_OPTS=-Xmx1g ./gradlew test -q 2>&1 | tail -5"}"""),
        )
        assertEquals(
            "Running cat build.log",
            labelAfter("Bash", """{"command":"set -e; MY=/x; cd /x; cat build.log"}"""),
        )
        assertEquals("Running python3", labelAfter("Bash", """{"command":"python3 - <<'EOF'\nprint(1)\nEOF"}"""))
        assertEquals("Running a command", labelAfter("Bash", """{"command":""}"""))
        assertEquals("Running a command", labelAfter("Bash", """{}"""))
    }

    @Test
    fun `search tools name the pattern and the rest their kind`() {
        assertEquals("Searching for perTurnHeaders", labelAfter("Grep", """{"pattern":"perTurnHeaders","path":"g"}"""))
        assertEquals("Finding **/*.kt", labelAfter("Glob", """{"pattern":"**/*.kt"}"""))
        assertEquals("Delegating to a subagent", labelAfter("Agent", """{"prompt":"x"}"""))
        assertEquals("Updating the task list", labelAfter("TodoWrite", """{"todos":[]}"""))
        assertEquals("Calling web_search_exa", labelAfter("mcp__exa__web_search_exa", """{"query":"x"}"""))
        assertEquals("Using Frobnicate", labelAfter("Frobnicate", """{}"""))
    }

    @Test
    fun `a long argument is clipped`() {
        val long = "a".repeat(60)
        assertEquals("Searching for ${"a".repeat(32)}…", labelAfter("Grep", """{"pattern":"$long"}"""))
    }

    @Test
    fun `the last tool call of the last assistant message wins`() {
        val twoCalls = """{"role":"assistant","content":[""" +
            """{"type":"tool_use","id":"a","name":"Read","input":{"file_path":"a.kt"}},""" +
            """{"type":"tool_use","id":"b","name":"Edit","input":{"file_path":"b.kt"}}]}"""
        val results = """{"role":"user","content":[""" +
            """{"type":"tool_result","tool_use_id":"a","content":"1"},""" +
            """{"type":"tool_result","tool_use_id":"b","content":"2"}]}"""
        assertEquals("Editing b.kt", label.labelFor(request(user, twoCalls, results, query)))
    }

    @Test
    fun `no tool call yet and no transcript have their own labels`() {
        val textOnly = """{"role":"assistant","content":[{"type":"text","text":"Here is the fix."}]}"""
        assertEquals("Replying to the user", label.labelFor(request(user, textOnly, query)))
        assertEquals("Reading the request", label.labelFor(request(query)))
    }

    @Test
    fun `anything but the verbatim side query at the head of the last user message is not answered`() {
        assertNull(label.labelFor(request(user, toolUse("Read", """{"file_path":"a"}"""), toolResult, user)))
        val reworded = """{"role":"user","content":"Describe your most recent action in 3 words."}"""
        assertNull(label.labelFor(request(user, reworded)))
        val quoted = """{"role":"user","content":"Why does Claude Code send \"Describe your most recent action """ +
            """in 3-5 words using present tense (-ing).\" every 30 seconds?"}"""
        assertNull(label.labelFor(request(quoted)))
        // The sentence in an assistant message, not the last user message.
        val assistantSays = """{"role":"assistant","content":"Describe your most recent action in 3-5 words using """ +
            """present tense (-ing)."}"""
        assertNull(label.labelFor(request(user, assistantSays)))
    }
}
