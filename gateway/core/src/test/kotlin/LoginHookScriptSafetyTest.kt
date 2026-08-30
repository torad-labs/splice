// NEW (review 2026-08-28, PR 99): the generated login/capture/advertiser hooks are bash scripts
// LoginInterception chmods 0700 and Claude Code executes on every prompt for a head — and every
// UX string in them is operator-authored (signInLabel is API_KEY_LABELS[provider] ?: provider,
// loginCommand is "${claude.command ?: key} login", envVar is the documented auth.env knob).
//
// Nothing executed these scripts before, so the two layers they have to satisfy were both unproven:
// an apostrophe in a label ended the single-quoted shell word early and handed the rest of the line
// to bash, and a quote or backslash corrupted the hand-built JSON object Claude Code parses as the
// hook's decision. Not a privilege boundary — the operator's daemon already runs as their uid — but
// a hook that breaks this way breaks SILENTLY, in a file nobody opens, on every prompt.
//
// These run bash for real, because "the string looks escaped" is exactly the claim that was wrong.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import splice.core.launch.LoginHookScripts
import splice.core.launch.LoginHookSpec
import splice.core.launch.TokenCaptureSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// Every character that used to break one of the two layers: an apostrophe closes a single-quoted
// shell word, a double quote and a backslash corrupt JSON, a newline escapes a `#` comment, and a
// semicolon would start a second command.
private const val HOSTILE_LABEL = "Ops' \"Prime\" \\ Co;\nsecond line"

private data class Ran(val exit: Int, val out: String, val err: String)

private fun bashAvailable(): Boolean =
    runCatching { ProcessBuilder("bash", "-c", "exit 0").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)

private fun run(vararg argv: String, stdin: String = "", dir: Path): Ran {
    val p = ProcessBuilder(*argv).directory(dir.toFile()).start()
    p.outputStream.use { it.write(stdin.toByteArray()) }
    val out = p.inputStream.readBytes().decodeToString()
    val err = p.errorStream.readBytes().decodeToString()
    p.waitFor(30, TimeUnit.SECONDS)
    return Ran(p.exitValue(), out, err)
}

private fun write(dir: Path, name: String, body: String): Path =
    Files.write(dir.resolve(name), body.toByteArray())

class LoginHookScriptSafetyTest {

    private val tmp: Path = Files.createTempDirectory("login-hook-safety")

    private fun spec(outcomeFile: String = "/nonexistent/receipt") = LoginHookSpec(
        loginCommand = "claude-splice login",
        signInLabel = HOSTILE_LABEL,
        viaBrowser = false, // never true here: the browser branch SPAWNS loginCommand
        sentinel = "SPLICE_CODEX_LOGIN",
        outcomeFile = outcomeFile,
        canCapturePaste = true,
    )

    @Test
    fun `every generated hook is valid bash with a hostile operator label`() {
        assumeTrue(bashAvailable(), "bash is required to check generated script syntax")
        val capture = TokenCaptureSpec("OPENROUTER_API_KEY", "sk-or-[A-Za-z0-9_-]{20,}", HOSTILE_LABEL)
        val scripts = mapOf(
            "login.sh" to LoginHookScripts.loginHookScript(spec()),
            "capture.sh" to LoginHookScripts.captureHookScript(capture),
            "keysetup.sh" to LoginHookScripts.keySetupScript(capture, "claude-splice login"),
        )
        scripts.forEach { (name, body) ->
            write(tmp, name, body)
            val checked = run("bash", "-n", name, dir = tmp)
            assertEquals(0, checked.exit, "$name is not valid bash: ${checked.err}")
        }
    }

    @Test
    fun `the login hook's block decision is parseable JSON carrying the label verbatim`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        write(tmp, "login-block.sh", LoginHookScripts.loginHookScript(spec()))
        val ran = run("bash", "login-block.sh", stdin = """{"prompt":"/login"}""", dir = tmp)
        assertEquals(0, ran.exit, ran.err)
        val decision = Json.parseToJsonElement(ran.out).jsonObject
        assertEquals("block", decision["decision"]?.jsonPrimitive?.content)
        val reason = decision["reason"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(reason.contains(HOSTILE_LABEL), "the label must survive both layers intact: $reason")
    }

    @Test
    fun `the receipt announcement is parseable JSON around the runtime message`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val receipt = tmp.resolve("receipt.txt")
        Files.write(receipt, "signed in as someone\"quoted".toByteArray())
        write(tmp, "login-receipt.sh", LoginHookScripts.loginHookScript(spec(receipt.toString())))
        val ran = run("bash", "login-receipt.sh", stdin = """{"prompt":"hello"}""", dir = tmp)
        assertEquals(0, ran.exit, ran.err)
        val ctx = Json.parseToJsonElement(ran.out)
            .jsonObject["hookSpecificOutput"]?.jsonObject?.get("additionalContext")?.jsonPrimitive?.content
        assertTrue(ctx.orEmpty().contains(HOSTILE_LABEL), "the label must survive: $ctx")
        assertTrue(ctx.orEmpty().contains("signed in as"), "the runtime receipt must ride: $ctx")
    }

    @Test
    fun `the advertiser prints its text unchanged rather than a truncated shell word`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val capture = TokenCaptureSpec("OPENROUTER_API_KEY", "sk-or-x", HOSTILE_LABEL)
        write(tmp, "advertise.sh", LoginHookScripts.keySetupScript(capture, "claude-splice login"))
        val ran = run("bash", "advertise.sh", dir = tmp)
        assertEquals(0, ran.exit, ran.err)
        assertTrue(ran.out.contains(HOSTILE_LABEL), "the label must survive the single-quoted word: ${ran.out}")
        assertTrue(ran.out.contains("Then wait."), "the text must not be truncated at the apostrophe: ${ran.out}")
    }

    // The one value that CANNOT be quoted away — it is a bare command word in the generated hook —
    // is refused where the spec is built instead, against KeyStore's own definition of an env name.
    @Test
    fun `an api-key env name that is not a valid env name is refused at construction`() {
        listOf("BAD;NAME", "A B", "lowercase", "1LEADING", "NAME'QUOTE", "").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java, { TokenCaptureSpec(bad, "sk-x", "P") }) {
                "TokenCaptureSpec must refuse '$bad' — it reaches a bare command word"
            }
        }
        TokenCaptureSpec("OPENROUTER_API_KEY", "sk-x", "P") // the documented shape still constructs
    }
}
