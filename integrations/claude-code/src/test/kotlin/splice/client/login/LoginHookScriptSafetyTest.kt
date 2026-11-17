package splice.client.login

// NEW (review 2026-08-28, PR 99): the generated login/capture/advertiser hooks are bash scripts Claude Code executes on
// every prompt, and the words in them are operator-authored, so these run bash for real: "the string looks escaped"
// is exactly the claim that was wrong.
//
// door-default (docs/specs/door-default.md, decision 5): /login opens splice's accounts page through `splice console`
// on the door and on every head here, and says where the logins live for a command another machine serves. The cases
// that pinned the head's own in-session sign-in (a spawned `<head> login`, its --label and the cancelling of a
// pending one) were deleted with it; signing in is done on the accounts page.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

// Every character that used to break one of the two layers: an apostrophe closes a single-quoted shell word, a double
// quote and a backslash corrupt JSON, a newline escapes a `#` comment, and a semicolon would start a second command.
private const val HOSTILE_LABEL = "Ops' \"Prime\" \\ Co;\nsecond line"

private data class Ran(val exit: Int, val out: String, val err: String)

private fun bashAvailable(): Boolean =
    runCatching { ProcessBuilder("bash", "-c", "exit 0").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)

private fun run(script: Path, stdin: String, dir: Path, path: String? = null): Ran {
    val builder = ProcessBuilder("bash", script.toString()).directory(dir.toFile())
    path?.let { builder.environment()["PATH"] = it }
    val p = builder.start()
    p.outputStream.use { it.write(stdin.toByteArray()) }
    val out = p.inputStream.readBytes().decodeToString()
    val err = p.errorStream.readBytes().decodeToString()
    p.waitFor(30, TimeUnit.SECONDS)
    return Ran(p.exitValue(), out, err)
}

private fun write(dir: Path, name: String, body: String): Path = Files.write(dir.resolve(name), body.toByteArray())

private fun decision(ran: Ran): String {
    assertEquals(0, ran.exit, ran.err)
    val decision = Json.parseToJsonElement(ran.out).jsonObject
    assertEquals("block", decision["decision"]?.jsonPrimitive?.content, ran.out)
    return decision["reason"]?.jsonPrimitive?.content.orEmpty()
}

class LoginHookScriptSafetyTest(@param:TempDir private val tmp: Path) {

    /** A PATH whose `splice` records each `splice console` it is asked for and prints [says], as the real one does. */
    private fun desk(says: String = "splice: opened the console at http://127.0.0.1:1"): String {
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val body = "#!/usr/bin/env bash\nprintf '%s\\n' \"\$*\" >> '$tmp/asked.txt'\ncat <<'SAID'\n$says\nSAID\n"
        write(bin, "splice", body).toFile().setExecutable(true)
        return "$bin:/usr/bin:/bin"
    }

    private fun asked(): List<String> =
        tmp.resolve("asked.txt").takeIf(Files::exists)?.let(Files::readAllLines).orEmpty()

    private val accounts: Path get() = write(tmp, "login.sh", LoginScript().hook())

    @Test
    fun `every generated hook is valid bash with a hostile operator label`() {
        assumeTrue(bashAvailable(), "bash is required to check generated script syntax")
        val capture = TokenCaptureSpec("OPENROUTER_API_KEY", "sk-or-[A-Za-z0-9_-]{20,}", HOSTILE_LABEL)
        val scripts = mapOf(
            "login.sh" to LoginScript(receipt = "/tmp/$HOSTILE_LABEL").hook(),
            "elsewhere.sh" to LoginScript(elsewhere = HOSTILE_LABEL).hook(),
            "capture.sh" to LoginHookScripts.captureHookScript(capture),
            "keysetup.sh" to LoginHookScripts.keySetupScript(capture, "claude-splice login"),
        )
        scripts.forEach { (name, body) ->
            val checked = ProcessBuilder("bash", "-n", write(tmp, name, body).toString()).start()
            val err = checked.errorStream.readBytes().decodeToString()
            checked.waitFor(10, TimeUnit.SECONDS)
            assertEquals(0, checked.exitValue(), "$name is not valid bash: $err")
        }
    }

    @Test
    fun `slash login in every spelling opens the accounts page and blocks the turn`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val path = desk()
        listOf(
            "/login",
            "/login ",
            "/login --label work",
            "/splice:login",
            "SPLICE_CODEX_LOGIN ",
            """/login\t--label ops\n""",
            """\/login""",
        ).forEachIndexed { i, prompt ->
            val ran = run(accounts, """{"prompt":"$prompt"}""", tmp, path)
            assertTrue(ran.out.isNotEmpty(), "$prompt is answered: ${ran.err}")
            val reason = decision(ran)
            assertTrue(reason.startsWith("Opened splice's accounts page"), "$prompt -> $reason")
            assertEquals(i + 1, asked().size, "$prompt runs splice console once")
        }
        assertTrue(asked().all { it == "console" }, asked().toString())
    }

    @Test
    fun `a prompt that is not slash login is an ordinary prompt and opens nothing`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val path = desk()
        listOf(
            """{"prompt":"/loginx"}""",
            """{"prompt":"how does /login work?"}""",
            """{"session_id":"s","prompt":"hello","metadata":{"prompt":"/login"}}""",
            """{"prompt":"see /login handler\n${"log line\\n".repeat(4_000)}"}""",
        ).forEach { input ->
            val ran = run(accounts, input, tmp, path)
            assertEquals(0, ran.exit, ran.err)
            assertEquals("", ran.out, input.take(80))
        }
        assertEquals(emptyList<String>(), asked())
    }

    @Test
    fun `an input that mentions slash login with no prompt to read is refused and opens nothing`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val reason = decision(run(accounts, """{"a":{"prompt":"/login"}}""", tmp, desk()))
        assertTrue(reason.startsWith("/login was seen, but this hook's input carries no prompt"), reason)
        assertEquals(emptyList<String>(), asked())
    }

    @Test
    fun `a console that does not open is said in its own words, inside parseable JSON`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val reason = decision(run(accounts, """{"prompt":"/login"}""", tmp, desk("splice: the \"daemon\" isn't \\ up")))
        assertTrue(reason.startsWith("splice's accounts page did not open"), reason)
        assertTrue(reason.contains("the \"daemon\" isn't \\ up"), reason)
        assertTrue(reason.contains("splice console"), reason)
    }

    @Test
    fun `a session without splice on its PATH is told so`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val empty = Files.createDirectories(tmp.resolve("empty"))
        val reason = decision(run(accounts, """{"prompt":"/login"}""", tmp, "$empty:/usr/bin:/bin"))
        assertTrue(reason.contains("not on the PATH"), reason)
    }

    @Test
    fun `a command another machine serves says where its logins live, verbatim, and opens nothing`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val hook = write(tmp, "elsewhere.sh", LoginScript(elsewhere = HOSTILE_LABEL).hook())
        assertEquals(HOSTILE_LABEL, decision(run(hook, """{"prompt":"/login"}""", tmp, desk())))
        assertEquals(emptyList<String>(), asked())
    }

    // door-default (decision 5): the file is laid in the person's own Claude folder, where a session with no hook reads
    // it, so after the sentinel line it says what it is and that `splice console` opens the page. This replaces the
    // arm that pinned the sentinel line as the file's end.
    @Test
    fun `the login command file submits the sentinel plus the arguments, described as splice's accounts`() {
        val md = LoginScript().command()
        assertTrue(Regex("(?m)^SPLICE_CODEX_LOGIN \\\$ARGUMENTS$").containsMatchIn(md), md)
        assertTrue("`splice console`" in md, md)
        assertTrue(Regex("description: .*account.*", RegexOption.IGNORE_CASE).containsMatchIn(md), md)
        assertTrue(md.contains("splice"), md)
    }

    @Test
    fun `the advertiser prints its text unchanged rather than a truncated shell word`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val capture = TokenCaptureSpec("OPENROUTER_API_KEY", "sk-or-x", HOSTILE_LABEL)
        val ran =
            run(write(tmp, "advertise.sh", LoginHookScripts.keySetupScript(capture, "claude-splice login")), "", tmp)
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

// DR-103: the generated hook is the PRODUCTION consumer of the login receipt a terminal `<head> login` leaves, and it
// must enforce the receipt's freshness itself: a stale failure from days ago must not announce on a fresh session.
class LoginHookReceiptAgeTest(@param:TempDir private val tmp: Path) {

    private fun hook(receipt: Path): Path = write(
        tmp,
        "login-receipt.sh",
        LoginScript(receipt = receipt.toString()).hook(),
    )

    private fun context(ran: Ran): String {
        assertEquals(0, ran.exit, ran.err)
        if (ran.out.isEmpty()) return ""
        return Json.parseToJsonElement(ran.out)
            .jsonObject["hookSpecificOutput"]?.jsonObject?.get("additionalContext")?.jsonPrimitive?.content.orEmpty()
    }

    @Test
    fun `a fresh receipt announces once, inside parseable JSON, and is consumed`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val receipt = tmp.resolve("receipt-fresh.txt")
        Files.writeString(receipt, "signed in as someone\"quoted\n")
        val ctx = context(run(hook(receipt), """{"prompt":"hello"}""", tmp))
        assertTrue(ctx.contains("signed in as someone\"quoted"), "a fresh receipt must announce; ctx=$ctx")
        assertTrue(!Files.exists(receipt), "the receipt is consumed on announce")
        assertEquals("", context(run(hook(receipt), """{"prompt":"hello"}""", tmp)), "once")
    }

    @Test
    fun `a stale receipt is consumed silently, never announced`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val receipt = tmp.resolve("receipt-stale.txt")
        Files.writeString(receipt, "sign-in did not complete\n")
        Files.setLastModifiedTime(receipt, FileTime.fromMillis(System.currentTimeMillis() - 11 * 60 * 1000))
        assertEquals("", context(run(hook(receipt), """{"prompt":"hello"}""", tmp)))
        assertTrue(!Files.exists(receipt), "a stale receipt is still consumed so it cannot linger")
    }

    // DR-137: the freshness check judges the receipt a link points at, as every other reader of it does.
    @Test
    fun `a symlink receipt is judged by its target's age, like every other reader`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val target = tmp.resolve("dr137-target.txt")
        Files.write(target, "sign-in failed: expired device code".toByteArray())
        Files.setLastModifiedTime(target, FileTime.fromMillis(0L))
        val link = Files.createSymbolicLink(tmp.resolve("dr137-link.txt"), target)
        assertEquals("", context(run(hook(link), """{"prompt":"hello"}""", tmp)))
    }
}
