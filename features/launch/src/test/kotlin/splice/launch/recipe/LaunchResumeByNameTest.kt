// NEW: Nov 16, 2026: `-r NAME`. Claude Code resolves a name among every command's sessions of the folder, so a name was
// a way around the copy a `-r SESSION_ID` of another command's session is given: `claude -r NAME` opened a session a
// GPT command wrote where it lay, asked Anthropic for the GPT model on every turn, and wrote the same transcript the
// other command was still writing. Each test here launches by name and reads what the person is told and what the
// client would be handed.
package splice.launch.recipe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.resume.ResumeAcrossHeads
import splice.client.resume.TranscriptModelRewrite
import splice.client.resume.originals.TranscriptOriginals
import splice.core.config.StatePaths
import splice.launch.GatewayBearer
import splice.launch.HeadTrees
import splice.launch.LaunchGateway
import splice.launch.LaunchModels
import splice.launch.LaunchOutcome
import splice.launch.LaunchSignIn
import splice.launch.LaunchSpec
import java.nio.file.Files
import java.nio.file.Path

private const val NIGHT_RUN = "11111111-2222-3333-4444-555555555555"
private const val OTHER_RUN = "66666666-7777-8888-9999-000000000000"

class LaunchResumeByNameTest(@param:TempDir private val tmp: Path) {

    private val service = LaunchService(
        ClaudeConfigMaterializer(tmp),
        resumeAcrossHeads = ResumeAcrossHeads(
            TranscriptModelRewrite(originals = TranscriptOriginals(StatePaths(baseOverride = tmp.resolve("state")))),
        ),
    )

    private val folder: Path = tmp.resolve(".claude-named/projects/-home-x")

    private val spec = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-named")),
        models = LaunchModels(
            pinnedModel = "gpt-5.6-sol",
            availableModelIds = listOf("gpt-5.6-sol", "gpt-5.4-mini"),
            modelLabels = mapOf("gpt-5.6-sol" to "gpt-5.6-sol", "gpt-5.4-mini" to "gpt-5.4-mini"),
            contextWindow = 272000,
            modelOptionsCache = kotlinx.serialization.json.buildJsonObject { },
        ),
        signIn = LaunchSignIn(loginCommand = "claudex login", signInLabel = "Codex (ChatGPT)"),
        gateway = LaunchGateway(
            statuslineCommand = "\"/bin/curl\" -s :3096/statusline",
            port = 3099,
            bearer = GatewayBearer.Planted("test-inference-token"),
            apiTimeoutMs = 960_000,
        ),
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
    )

    /** A session of the folder `/home/x` named [name], whose one answer is on [model]. */
    private fun session(id: String, name: String, model: String): Path {
        val transcript = folder.resolve("$id.jsonl")
        Files.createDirectories(folder)
        Files.writeString(
            transcript,
            """{"type":"custom-title","customTitle":"$name","sessionId":"$id"}
{"type":"user","sessionId":"$id","cwd":"/home/x","message":{"role":"user","content":"hi"}}
{"type":"assistant","sessionId":"$id","message":{"id":"m1","model":"$model","content":[{"type":"text","text":"hello"}]}}
""",
        )
        return transcript
    }

    private fun launch(vararg args: String): LaunchOutcome = service.launchOutcome(
        spec,
        args.toList(),
        dangerouslySkipPermissions = false,
        caller = LaunchCaller(cwd = "/home/x"),
    )

    private fun inTheFolder(): List<String> =
        Files.list(folder).use { all -> all.map { it.fileName.toString() }.toList() }

    private fun handedToTheClient(outcome: LaunchOutcome): List<String> =
        (outcome as? LaunchOutcome.Ready)?.recipe?.argv ?: error("the launch was refused: $outcome")

    @Test
    fun `a name of a session on a model this command does not serve is refused, and nothing is opened or written`() {
        val transcript = session(NIGHT_RUN, "night-run", model = "k3-256k")
        val before = Files.readString(transcript)

        val outcome = launch("-r", "night-run")

        val refused = outcome as? LaunchOutcome.Refused ?: error("the client would have been started: $outcome")
        assertTrue(refused.reason.contains("-r $NIGHT_RUN"), "the id a copy is made by: ${refused.reason}")
        assertEquals(before, Files.readString(transcript), "the session is as it was")
        assertEquals(listOf("$NIGHT_RUN.jsonl"), inTheFolder(), "and no copy of it was made")
    }

    @Test
    fun `the id the refusal gives resumes the session as a copy on this command's model`() {
        session(NIGHT_RUN, "night-run", model = "k3-256k")

        val recipe = (launch("-r", NIGHT_RUN) as? LaunchOutcome.Ready)?.recipe ?: error("the id was refused")

        assertTrue(recipe.warning.orEmpty().contains("goes on here as its copy"), "said in words: ${recipe.warning}")
        assertTrue(NIGHT_RUN !in recipe.argv, "the client resumes the copy, not the session itself: ${recipe.argv}")
    }

    @Test
    fun `every spelling of a named resume is refused alike, whatever the letter case`() {
        session(NIGHT_RUN, "night-run", model = "k3-256k")

        for (args in listOf(listOf("--resume", "night-run"), listOf("-r=night-run"), listOf("--resume=Night-Run"))) {
            assertTrue(launch(*args.toTypedArray()) is LaunchOutcome.Refused, "$args started the client")
        }
    }

    @Test
    fun `a name of a session on this command's own model goes to the client as it was typed`() {
        session(NIGHT_RUN, "night-run", model = "gpt-5.6-sol")

        assertEquals(listOf("-r", "night-run"), handedToTheClient(launch("-r", "night-run")).takeLast(2))
    }

    @Test
    fun `a name two sessions carry is the client's picker's to ask about`() {
        session(NIGHT_RUN, "night-run", model = "k3-256k")
        session(OTHER_RUN, "night-run", model = "k3-256k")

        assertEquals(listOf("-r", "night-run"), handedToTheClient(launch("-r", "night-run")).takeLast(2))
    }

    @Test
    fun `a word no session is named goes to the client as it was typed`() {
        session(NIGHT_RUN, "night-run", model = "k3-256k")

        assertEquals(listOf("-r", "day-run"), handedToTheClient(launch("-r", "day-run")).takeLast(2))
    }
}
