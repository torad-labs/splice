package splice.app.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import splice.core.terminal.TerminalOutput
import splice.topology.ConfigFindings
import splice.topology.ConfigRead
import java.nio.file.Files
import java.nio.file.Path

/** What the fix session's tests share: a session over a file with scripted replies, a file whose head serves one
 *  provider, and the check that a fixed file boots with nothing left to say. */
abstract class ConfigFixRig {
    /** A session over the file's findings, or with [advisory] over the warnings boot serves past
     *  (`check-config --fix`), a finding a move would add counted too. */
    internal fun session(
        file: Path,
        root: Path,
        replies: ArrayDeque<String>,
        said: MutableList<String>,
        advisory: Boolean = false,
    ) =
        ConfigFixSession(
            file,
            TerminalOutput { said += it },
            object : FixPrompter {
                override fun ask(question: String): String? = replies.removeFirstOrNull()

                override fun secret(question: String): String? = replies.removeFirstOrNull()

                override fun edit(file: Path, line: Int?): Boolean = false
            },
            { text ->
                when (val read = ConfigFindings.read(text, root)) {
                    is ConfigRead.Refused -> read.findings
                    is ConfigRead.Ready -> if (advisory) read.warnings else emptyList()
                }
            },
            { source -> Files.copy(source, root.resolve("backup.toml")) },
            advisory,
        )

    /** A provider with one declared model, then [extra] under the head `one`, which serves it. */
    internal fun served(root: Path, providerExtra: String, headExtra: String): Path {
        val file = root.resolve("splice.toml")
        Files.writeString(
            file,
            """
            [providers.demo]
            dialect = "openai-chat"
            base_url = "https://demo.invalid"
            auth = { kind = "api-key", env = "DEMO_KEY" }
            """.trimIndent() + "\n" + providerExtra + """

            [[providers.demo.models]]
            id = "m"
            context_window = 1000

            [[providers.demo.models]]
            id = "s"
            context_window = 1000

            [heads.one]
            provider = "demo"
            port = 3101
            discovery_prefix = "claude-demo--"
            pinned_model = "m"
            """.trimIndent() + "\n" + headExtra,
        )
        return file
    }

    /** The file boots, and says nothing it serves past: every warning was moved too. */
    internal fun readsClean(file: Path, root: Path) {
        val read = ConfigFindings.read(Files.readString(file), root)
        assertTrue(read is ConfigRead.Ready, Files.readString(file))
        assertEquals(emptyList<Any>(), (read as ConfigRead.Ready).warnings, Files.readString(file))
    }
}
