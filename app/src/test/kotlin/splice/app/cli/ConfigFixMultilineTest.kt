package splice.app.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The fix session reads splice.toml statement by statement: a line inside a multi-line string is never a table or a
 *  key it moves to or from, and a value an inline table cannot hold as written is never offered a move into one. */
class ConfigFixMultilineTest : ConfigFixRig() {
    /** [served] with `[providers.other]`, whose own multi-line prompt shows a `[providers.demo]` table as an example,
     *  written ahead of the real one. */
    private fun behindAnExample(root: Path, headExtra: String): Path {
        val file = served(root, "", headExtra)
        val other = "[providers.other]\ndialect = \"openai-chat\"\nbase_url = \"https://other.invalid\"\n" +
            "auth = { kind = \"api-key\", env = \"OTHER_KEY\" }\nsystem_prompt = \"\"\"\nWrite it like this:\n" +
            "[providers.demo]\nsystem_prompt = 'kept'\n\"\"\"\n\n[[providers.other.models]]\nid = \"o\"\n" +
            "context_window = 1000\n\n"
        Files.writeString(file, other + Files.readString(file))
        return file
    }

    @Test
    fun `a table header written inside another provider's multi-line prompt is not where a move lands`(
        @TempDir root: Path,
    ) {
        val file = behindAnExample(root, "system_prompt = \"Review as the demo team.\"\n")
        val said = mutableListOf<String>()

        val fixed = session(file, root, ArrayDeque(listOf("m")), said, advisory = true).run()

        val text = Files.readString(file)
        assertTrue(fixed, "$said\n$text")
        assertTrue(
            text.contains("Write it like this:\n[providers.demo]\nsystem_prompt = 'kept'\n\"\"\"\n"),
            "the other provider's prompt is unchanged: $text",
        )
        val real = "\n\n[providers.demo]\nsystem_prompt = \"Review as the demo team.\"\ndialect = "
        assertTrue(text.contains(real), "it lands under the real header: $text")
        readsClean(file, root)
    }

    @Test
    fun `a head's multi-line prompt moves whole and a line inside it is never taken for its mode`(
        @TempDir root: Path,
    ) {
        val fake = "system_prompt_mode = \"replace\"\n[providers.demo]\n"
        val literal = "system_prompt = '''\n$fake[not.a.table]\nC:\\'''\n"
        val basic = "system_prompt = \"\"\"\nSay \"this\".\n$fake[not.a.table]\nEnd.\"\"\"\n"
        val mode = "system_prompt_mode = \"append\" # appended on purpose\n"
        listOf(literal, basic).forEachIndexed { case, prompt ->
            val dir = Files.createDirectories(root.resolve("case$case"))
            val file = served(dir, "", prompt + mode)
            val said = mutableListOf<String>()

            val fixed = session(file, dir, ArrayDeque(listOf("m")), said, advisory = true).run()

            val text = Files.readString(file)
            assertTrue(fixed, "$said\n$text")
            assertTrue(text.startsWith("[providers.demo]\n$prompt$mode"), "both land under the real header: $text")
            val head = text.substringAfter("[heads.one]")
            assertFalse(head.contains("system_prompt"), "every line of the head's prompt is taken off: $text")
            readsClean(file, dir)
        }
    }

    @Test
    fun `a head written as an example in a multi-line prompt above it never hides the real head's move`(
        @TempDir root: Path,
    ) {
        val example = "[providers.other]\ndialect = \"openai-chat\"\nbase_url = \"https://other.invalid\"\n" +
            "auth = { kind = \"api-key\", env = \"OTHER_KEY\" }\nsystem_prompt = '''\nA head looks like this:\n" +
            "[heads.one]\nsystem_prompt = \"not this one\"\n'''\n\n[[providers.other.models]]\nid = \"o\"\n" +
            "context_window = 1000\n\n"
        listOf("\n", "\r\n").forEachIndexed { case, end ->
            val dir = Files.createDirectories(root.resolve("case$case"))
            val file = served(dir, "", "system_prompt = \"Review as the demo team.\"\n")
            Files.writeString(file, (example + Files.readString(file)).replace("\n", end))
            val said = mutableListOf<String>()

            val fixed = session(file, dir, ArrayDeque(listOf("m")), said, advisory = true).run()

            val text = Files.readString(file)
            assertTrue(fixed, "$said\n$text")
            val lf = text.replace("\r\n", "\n")
            assertTrue(lf.startsWith(example), "the example is unchanged: $text")
            val real = "\n[providers.demo]\nsystem_prompt = \"Review as the demo team.\"\ndialect = "
            assertTrue(lf.contains(real), "the real head's prompt moves: $text")
            assertFalse(lf.substringAfter("[heads.one]\nprovider").contains("system_prompt"), text)
            val bare = Regex("(?<!\r)\n").containsMatchIn(text)
            assertEquals(end == "\n", bare, "the file keeps the line ends it was written with: $text")
            readsClean(file, dir)
        }
    }

    /** A splice.toml whose provider is an inline table in `[providers]`, and whose head says [headExtra]. */
    private fun inlineProvider(root: Path, headExtra: String): Path = root.resolve("splice.toml").also {
        Files.writeString(
            it,
            "[providers]\ndemo = { dialect = \"openai-chat\", base_url = \"https://demo.invalid\", " +
                "auth = { kind = \"api-key\", env = \"DEMO_KEY\" }, " +
                "models = [{ id = \"m\", context_window = 1000 }] }\n\n[heads.one]\nprovider = \"demo\"\n" +
                "port = 3101\ndiscovery_prefix = \"claude-demo--\"\npinned_model = \"m\"\n" + headExtra,
        )
    }

    @Test
    fun `a one-line prompt moves into an inline provider table`(@TempDir root: Path) {
        val file = inlineProvider(root, "system_prompt = \"Review as the demo team.\"\n")

        val fixed = session(file, root, ArrayDeque(listOf("m")), mutableListOf(), advisory = true).run()

        val text = Files.readString(file)
        assertTrue(fixed, text)
        val inline = "models = [{ id = \"m\", context_window = 1000 }], system_prompt = \"Review as the demo team.\" }"
        assertTrue(text.contains(inline), text)
        readsClean(file, root)
    }

    @Test
    fun `no move is offered into an inline provider table for a value it cannot hold as written`(
        @TempDir root: Path,
    ) {
        val multiline = "system_prompt = \"\"\"\nReview\nas the team.\"\"\"\n"
        for (prompt in listOf(multiline, "system_prompt = \"Review.\" # why\n")) {
            val file = inlineProvider(root, prompt)
            val before = Files.readString(file)
            val said = mutableListOf<String>()

            val fixed = session(file, root, ArrayDeque(listOf("m", "s")), said, advisory = true).run()

            assertFalse(fixed, "$said")
            assertEquals(before, Files.readString(file), "nothing is written")
            assertFalse(said.any { it.contains("[m]") }, "no move is offered only to be refused: $said")
            assertFalse(said.any { it.contains("cannot be moved") }, "$said")
        }
    }
}
