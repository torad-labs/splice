// NEW: V4-115 redo — the five resume spellings and the refusal, split out of LaunchServiceTest.kt
// so that class stays under its size ceiling (LargeClass). The five spellings Claude Code admits
// for a named resume — `-r <id>`, `--resume <id>`, `-r=<id>`, `--resume=<id>`, and the glued
// `-r<id>` — all reach the same resolver. Each form is its own test with its own session id (a
// copy is refused when the target already holds the id), and each asserts the transcript landed
// in THIS head's tree: that is what proves the form was resolved to the id it named, not merely
// parsed. Two of these were broken and no test touched them: `--resume <id>` took substring(2)
// on a length-8 flag and resolved the literal id resume (a silent wrong-session resume), and the
// glued `-r<id>` was not matched by the finder at all, though the resolver's own comment claimed
// it.
package splice.control

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import java.nio.file.Files
import java.nio.file.Path

class LaunchResumeSpellingsTest {

    private val tmp = Files.createTempDirectory("launch-resume-spellings-test")
    private val service = LaunchService(ClaudeConfigMaterializer(tmp))

    private fun spec(
        head: String,
        pinned: String = "gpt-5.6-sol",
        available: List<String> = listOf("gpt-5.6-sol", "gpt-5.4-mini"),
        labels: Map<String, String> = available.associateWith { it },
    ) = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-$head")),
        pinnedModel = pinned,
        availableModelIds = available,
        modelLabels = labels,
        contextWindow = 272000,
        modelOptionsCache = kotlinx.serialization.json.buildJsonObject { },
        statuslineCommand = "\"/bin/curl\" -s :3096/statusline",
        loginCommand = "claudex login",
        signInLabel = "Codex (ChatGPT)",
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        port = 3099,
        inferenceToken = "test-inference-token",
        apiTimeoutMs = 960_000,
    )

    private fun spellingsSpec(sibling: Path) =
        spec("spellings").copy(trees = HeadTrees(tmp.resolve(".claude-spellings"), listOf(sibling)))

    @Test
    fun `-r with a space copies the transcript from the next argument`() {
        val sibling = seedSiblingSession("abc-123")
        service.launch(spellingsSpec(sibling), extraArgs = listOf("-r", "abc-123"), dangerouslySkipPermissions = false)
        assertTrue(
            Files.isRegularFile(tmp.resolve(".claude-spellings/projects/-home-x/abc-123.jsonl")),
            "-r <id>: the id is the next argument",
        )
    }

    @Test
    fun `--resume with a space copies the transcript from the next argument`() {
        val sibling = seedSiblingSession("abc-124")
        service.launch(
            spellingsSpec(sibling),
            extraArgs = listOf("--resume", "abc-124"),
            dangerouslySkipPermissions = false,
        )
        assertTrue(
            Files.isRegularFile(tmp.resolve(".claude-spellings/projects/-home-x/abc-124.jsonl")),
            "--resume <id>: the id is the next argument, never the literal id resume",
        )
    }

    @Test
    fun `-r=id copies the transcript from after the equals`() {
        val sibling = seedSiblingSession("abc-125")
        service.launch(spellingsSpec(sibling), extraArgs = listOf("-r=abc-125"), dangerouslySkipPermissions = false)
        assertTrue(
            Files.isRegularFile(tmp.resolve(".claude-spellings/projects/-home-x/abc-125.jsonl")),
            "-r=<id>: the id is after the equals",
        )
    }

    @Test
    fun `--resume=id copies the transcript from after the equals`() {
        val sibling = seedSiblingSession("abc-126")
        service.launch(
            spellingsSpec(sibling),
            extraArgs = listOf("--resume=abc-126"),
            dangerouslySkipPermissions = false,
        )
        assertTrue(
            Files.isRegularFile(tmp.resolve(".claude-spellings/projects/-home-x/abc-126.jsonl")),
            "--resume=<id>: the id is after the equals",
        )
    }

    @Test
    fun `glued -rID copies the transcript via substring 2`() {
        val sibling = seedSiblingSession("abc-127")
        service.launch(spellingsSpec(sibling), extraArgs = listOf("-rabc-127"), dangerouslySkipPermissions = false)
        assertTrue(
            Files.isRegularFile(tmp.resolve(".claude-spellings/projects/-home-x/abc-127.jsonl")),
            "-r<id> glued: substring(2) applies only to -r followed by a non-flag character",
        )
    }

    // A named `-r` id no head holds is refused in words rather than silently launching into a
    // session that is not there.
    @Test
    fun `a named -r id no head holds is refused in words`() {
        val sibling = seedSiblingSession("abc-123")
        val mine = spellingsSpec(sibling)

        val missing = service.launch(
            mine,
            extraArgs = listOf("-r", "99999999-8888-7777-6666-555555555555"),
            dangerouslySkipPermissions = false,
        )
        assertTrue(
            missing.warning.orEmpty().contains("is in no transcript tree"),
            "the refusal names the id and says nothing was copied: ${missing.warning}",
        )
    }

    /** A SIBLING head's projects tree holding [sessionId] under the encoded cwd `-home-x`. The head
     *  name is fixed and distinct from every calling head in these tests: seeding the CALLING head's
     *  own dir would make the adoption a no-op (HeadOwned) and pin nothing. */
    private fun seedSiblingSession(sessionId: String): Path {
        val path = tmp.resolve(".claude-sibling/projects/-home-x/$sessionId.jsonl")
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            """{"type":"user","sessionId":"$sessionId","message":{"role":"user","content":"hi"}}
{"type":"assistant","sessionId":"$sessionId","message":{"id":"m1","model":"k3-256k","content":[]}}
""",
        )
        return tmp.resolve(".claude-sibling")
    }
}
