// NEW (review 2026-08-28, PR 99): the generated login/capture/advertiser hooks are bash scripts
// LoginInterception chmods 0700 and Claude Code executes on every prompt for a head — and every
// UX string in them is operator-authored (signInLabel is AuthKind.signInLabel or the
// ApiKeyProviderRegistry row label, else the provider id; loginCommand is
// "${claude.command ?: key} login", envVar is the documented auth.env knob).
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
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider

// Every character that used to break one of the two layers: an apostrophe closes a single-quoted
// shell word, a double quote and a backslash corrupt JSON, a newline escapes a `#` comment, and a
// semicolon would start a second command.
private const val HOSTILE_LABEL = "Ops' \"Prime\" \\ Co;\nsecond line"

private data class Ran(val exit: Int, val out: String, val err: String)

private fun bashAvailable(): Boolean =
    runCatching { ProcessBuilder("bash", "-c", "exit 0").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)

private fun run(vararg argv: String, stdin: String = "", dir: Path, env: Map<String, String> = emptyMap()): Ran {
    val builder = ProcessBuilder(*argv).directory(dir.toFile())
    builder.environment().putAll(env)
    val p = builder.start()
    p.outputStream.use { it.write(stdin.toByteArray()) }
    val out = p.inputStream.readBytes().decodeToString()
    val err = p.errorStream.readBytes().decodeToString()
    p.waitFor(30, TimeUnit.SECONDS)
    return Ran(p.exitValue(), out, err)
}

private fun write(dir: Path, name: String, body: String): Path =
    Files.write(dir.resolve(name), body.toByteArray())

/** The real JVMs the pending-sign-in cases need: a parked one in the shim's exact invocation, unrelated
 *  JVMs and bash bystanders that carry the words but are not ours. */
private object LoginProcesses {
    private val javaBin: String = Path.of(System.getProperty("java.home"), "bin", "java").toString()

    /** A REAL JVM parked the way `splice.jar login` parks on its loopback listener: a tiny jar
     *  compiled here whose main sleeps; with `stubborn` as its last argument it registers a slow
     *  shutdown hook, so TERM does not end it within the hook's 2 s (the JVM mid-shutdown-hook case). */
    fun parkJar(dir: Path): Path {
        val jar = dir.resolve("park.jar")
        if (Files.exists(jar)) return jar
        val compiler = ToolProvider.getSystemJavaCompiler()
        assumeTrue(compiler != null, "a JDK compiler is required to build the parked-JVM stand-in")
        val src = write(
            dir,
            "Park.java",
            """
            public class Park {
                public static void main(String[] args) throws Exception {
                    if (args.length > 0 && args[args.length - 1].equals("stubborn")) {
                        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                            try { Thread.sleep(30000); } catch (InterruptedException ignored) { }
                        }));
                    }
                    Thread.sleep(60000);
                }
            }
            """.trimIndent(),
        )
        val classes = Files.createDirectories(dir.resolve("park-classes"))
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), src.toString()), "javac")
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name.MAIN_CLASS] = "Park"
        JarOutputStream(Files.newOutputStream(jar), manifest).use { out ->
            out.putNextEntry(JarEntry("Park.class"))
            out.write(Files.readAllBytes(classes.resolve("Park.class")))
            out.closeEntry()
        }
        return jar
    }

    /** The shim's exact invocation (`java -jar <jar> login <head>`, a real java executable), waiting;
     *  [hookStarted] marks it the way the hook marks every login it spawns. */
    fun pendingLogin(
        recorder: Path,
        jar: Path,
        ignoreTerm: Boolean,
        hookStarted: Boolean = true,
        word: String = recorder.toString(),
    ): Process {
        val argv = mutableListOf(javaBin, "-jar", jar.toString(), "login", word)
        if (ignoreTerm) argv += "stubborn"
        val builder = ProcessBuilder(argv)
        if (hookStarted) builder.environment()["SPLICE_LOGIN_ORIGIN"] = "hook"
        return builder.start()
    }

    /** An UNRELATED JVM (the same real java executable) whose main is something else and whose
     *  own application arguments carry `-jar <the splice jar> login <head>`: in jar mode (another
     *  main jar) or class mode. Fixed positions decide, so neither is ours. */
    fun otherJvm(dir: Path, recorder: Path, spliceJar: Path, classMode: Boolean): Process {
        val park = parkJar(dir)
        val launch = if (classMode) {
            listOf("-cp", dir.resolve("park-classes").toString(), "Park")
        } else {
            val other = dir.resolve("report.jar")
            if (!Files.exists(other)) Files.copy(park, other)
            listOf("-jar", other.toString(), "--inspect")
        }
        val tail = listOf("-jar", spliceJar.toString(), "login", recorder.toString())
        return ProcessBuilder(listOf(javaBin) + launch + tail).start()
    }

    /** A process whose command line carries the login words but whose executable is bash: the
     *  words are one argv element ([oneWord]) or separate ones, and argv[0] may even say java. */
    fun bystander(recorder: Path, oneWord: Boolean, argv0Java: Boolean = false): Process {
        val loop = "while :; do sleep 1; done"
        val cmd = if (oneWord) {
            "exec -a 'java -jar /x/splice.jar login $recorder' sleep 60"
        } else {
            val prefix = if (argv0Java) "exec -a java " else "exec "
            prefix + "bash -c '$loop' x java -jar /x/splice.jar login '$recorder'"
        }
        return ProcessBuilder("bash", "-c", cmd).start()
    }
}

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

    /** The browser branch SPAWNS loginCommand: here it is a recorder script, so the spawn is
     *  observable and harmless. Its path is the head word the pending-process pattern is built from. */
    private fun browserSpec(recorder: Path) = LoginHookSpec(
        loginCommand = "$recorder login",
        signInLabel = "Codex (ChatGPT)",
        viaBrowser = true,
        sentinel = "SPLICE_CODEX_LOGIN",
        outcomeFile = "/nonexistent/receipt",
        canCapturePaste = false,
        headKey = "codex",
    )

    private fun recorder(dir: Path): Path {
        // Written whole, then renamed: the reader polls for the file, so a partial write is never seen.
        val body = "#!/usr/bin/env bash\nprintf '%s\\n' \"\$@\" > \"$dir/args.tmp\" && " +
            "mv \"$dir/args.tmp\" \"$dir/args.txt\"\n"
        val script = write(dir, "recorder.sh", body)
        script.toFile().setExecutable(true)
        return script
    }

    private fun recordedArgs(dir: Path): List<String> {
        val file = dir.resolve("args.txt")
        repeat(50) { if (Files.exists(file)) return Files.readAllLines(file) else Thread.sleep(100) }
        return emptyList()
    }

    private fun decision(ran: Ran): String {
        assertEquals(0, ran.exit, ran.err)
        val decision = Json.parseToJsonElement(ran.out).jsonObject
        assertEquals("block", decision["decision"]?.jsonPrimitive?.content, ran.out)
        return decision["reason"]?.jsonPrimitive?.content.orEmpty()
    }

    @Test
    fun `slash login with a trailing space or arguments is intercepted, and --label rides to the CLI - V4-13`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val hook = write(tmp, "login-args.sh", LoginHookScripts.loginHookScript(browserSpec(recorder(tmp))))
        val spaced = run("bash", hook.toString(), stdin = """{"prompt":"/login "}""", dir = tmp)
        assertTrue(decision(spaced).startsWith("Opening your browser"), spaced.out)
        assertEquals(listOf("login"), recordedArgs(tmp), "no label: the plain login command")
        Files.delete(tmp.resolve("args.txt"))
        val labeled = run("bash", hook.toString(), stdin = """{"prompt": "/login --label work"}""", dir = tmp)
        decision(labeled)
        assertEquals(listOf("login", "--label", "work"), recordedArgs(tmp), "--label rides through")
        Files.delete(tmp.resolve("args.txt"))
        val body = """{"prompt":"SPLICE_CODEX_LOGIN --label ops.2"}"""
        val expanded = run("bash", hook.toString(), stdin = body, dir = tmp)
        decision(expanded)
        assertEquals(listOf("login", "--label", "ops.2"), recordedArgs(tmp), "the expanded command form too")
        val other = run("bash", hook.toString(), stdin = """{"prompt":"/loginx"}""", dir = tmp)
        assertEquals(0, other.exit, other.err)
        assertEquals("", other.out, "/loginx is not /login")
        val prose = run("bash", hook.toString(), stdin = """{"prompt":"how does /login work?"}""", dir = tmp)
        assertEquals("", prose.out, "a sentence mentioning /login is a prompt")
        Files.delete(tmp.resolve("args.txt"))
        // Claude Code writes a typed tab or newline into the JSON as the escape, not the character.
        val escaped = run("bash", hook.toString(), stdin = """{"prompt":"/login\t--label ops\n"}""", dir = tmp)
        decision(escaped)
        assertEquals(listOf("login", "--label", "ops"), recordedArgs(tmp), "escaped whitespace is whitespace")
        Files.delete(tmp.resolve("args.txt"))
        val newline = run("bash", hook.toString(), stdin = """{"prompt":"/login\n"}""", dir = tmp)
        assertTrue(decision(newline).startsWith("Opening your browser"), newline.out)
        assertEquals(listOf("login"), recordedArgs(tmp), "a trailing escaped newline is /login")
    }

    /** A label the CLI would refuse must not fall back to a bare login: that signs the PRIMARY in
     *  again and overwrites its credential. The hook refuses first and starts nothing. */
    @Test
    fun `an argument the login command would refuse is refused by the hook and nothing starts - V4-13`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val hook = write(tmp, "login-bad.sh", LoginHookScripts.loginHookScript(browserSpec(recorder(tmp))))
        val long = "a".repeat(49)
        listOf(
            """/login --label \"my work\"""",
            "/login --label my work",
            "/login --label Work",
            "/login --label -x",
            "/login --label $long",
            "/login --label",
            "/login --force",
            "SPLICE_CODEX_LOGIN --label Work",
            """SPLICE_CODEX_LOGIN\t--label Work""",
            """SPLICE_CODEX_LOGIN\n--label Work\n""",
        ).forEach { prompt ->
            val ran = run("bash", hook.toString(), stdin = """{"prompt":"$prompt"}""", dir = tmp)
            val reason = decision(ran)
            assertTrue(reason.startsWith("/login takes no arguments other than --label NAME"), "$prompt -> $reason")
            Thread.sleep(150)
            assertTrue(!Files.exists(tmp.resolve("args.txt")), "$prompt must start nothing")
        }
        val equals = run("bash", hook.toString(), stdin = """{"prompt":"/login --label=work-2"}""", dir = tmp)
        decision(equals)
        assertEquals(listOf("login", "--label", "work-2"), recordedArgs(tmp), "--label=NAME is the same flag")
        Files.delete(tmp.resolve("args.txt"))
        val cwd = """{"prompt":"/login","cwd":"/home/x/--label evil"}"""
        decision(run("bash", hook.toString(), stdin = cwd, dir = tmp))
        assertEquals(listOf("login"), recordedArgs(tmp), "arguments are read from the prompt field only")
    }

    @Test
    fun `the top-level prompt is decoded whatever the field order, nesting or escapes - V4-13`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val hook = write(tmp, "login-json.sh", LoginHookScripts.loginHookScript(browserSpec(recorder(tmp))))
        // Only the TOP-LEVEL prompt is read: a nested object with its own prompt key is data.
        val nested = """{"session_id":"s","prompt":"hello","metadata":{"prompt":"/login --label work"}}"""
        val nestedRan = run("bash", hook.toString(), stdin = nested, dir = tmp)
        assertEquals("", nestedRan.out, "a nested prompt key is not the prompt")
        Thread.sleep(150)
        assertTrue(!Files.exists(tmp.resolve("args.txt")), "nothing started for a nested prompt")
        // Field order and nesting before the prompt do not matter: the top-level prompt is decoded.
        listOf(
            """{"metadata":{"prompt":"x"},"prompt":"/login"}""" to listOf("login"),
            """{"x":{"y":[1,{"prompt":"z"}]},"prompt":"SPLICE_CODEX_LOGIN --label ops"}""" to
                listOf("login", "--label", "ops"),
            """{"prompt":"\/login --label=a.b","cwd":"/x"}""" to listOf("login", "--label", "a.b"),
            """{"prompt":"/login\t--label q\"x\"","p":"\"prompt\":\"/login\""}""" to null,
        ).forEach { (input, expected) ->
            val ran = run("bash", hook.toString(), stdin = input, dir = tmp)
            val reason = decision(ran)
            if (expected == null) {
                assertTrue(reason.startsWith("/login takes no arguments"), "$input -> $reason")
                Thread.sleep(150)
                assertTrue(!Files.exists(tmp.resolve("args.txt")), "$input must start nothing")
            } else {
                assertEquals(expected, recordedArgs(tmp), input)
                Files.delete(tmp.resolve("args.txt"))
            }
        }
        // A readable top-level prompt that is not /login is an ordinary prompt, whatever else the
        // input carries; an input with no top-level prompt string that mentions /login is refused.
        val ordinary = run("bash", hook.toString(), stdin = """{"a":{"prompt":"/login"},"prompt":"hello"}""", dir = tmp)
        assertEquals("", ordinary.out, "the top-level prompt is hello")
        val none = run("bash", hook.toString(), stdin = """{"a":{"prompt":"/login"}}""", dir = tmp)
        assertTrue(decision(none).startsWith("/login was seen but this hook input carries no prompt"), none.out)
        Thread.sleep(150)
        assertTrue(!Files.exists(tmp.resolve("args.txt")), "an unreadable input starts nothing")
    }

    @Test
    fun `a large paste that mentions slash login is an ordinary prompt and is never scanned - V4-13`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val hook = write(tmp, "login-large.sh", LoginHookScripts.loginHookScript(browserSpec(recorder(tmp))))
        val paste = "see /login handler\\n" + "log line\\n".repeat(120_000)
        val started = System.nanoTime()
        val ran = run("bash", hook.toString(), stdin = """{"prompt":"$paste"}""", dir = tmp)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(0, ran.exit, ran.err)
        assertEquals("", ran.out, "a mention inside a paste is not a login")
        assertTrue(elapsedMs < 3_000, "the hook answered in ${elapsedMs}ms: the scan must not walk a paste")
        Thread.sleep(150)
        assertTrue(!Files.exists(tmp.resolve("args.txt")), "nothing started")
    }

    @Test
    fun `the login command file expands to the sentinel plus the arguments - V4-13`() {
        val md = LoginHookScripts.loginCommandMd("Codex (ChatGPT)", "SPLICE_CODEX_LOGIN")
        assertTrue(md.endsWith("SPLICE_CODEX_LOGIN \$ARGUMENTS\n"), md)
        assertTrue(md.contains("argument-hint: \"[--label NAME]\""), md)
    }

    @Test
    fun `a sign-in still waiting for its callback is cancelled before a new one starts - V4-13`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val recorder = recorder(tmp)
        val hook = write(tmp, "login-pending.sh", LoginHookScripts.loginHookScript(browserSpec(recorder)))
        // A jar path with spaces, as SPLICE_JAR may hold and the shim quotes through.
        val jar = Files.createDirectories(tmp.resolve("my dir")).resolve("custom-0.4.0.jar")
        Files.copy(LoginProcesses.parkJar(tmp), jar)
        val pending = LoginProcesses.pendingLogin(recorder, jar, ignoreTerm = false)
        // Bystanders: the words as one argv element; as separate elements under a bash executable;
        // argv[0] claiming java over a bash executable; a JVM in jar mode and one in class mode
        // carrying the splice sequence in their own arguments. None is ever signalled.
        val bystanders = listOf(
            LoginProcesses.bystander(recorder, oneWord = true),
            LoginProcesses.bystander(recorder, oneWord = false),
            LoginProcesses.bystander(recorder, oneWord = false, argv0Java = true),
            LoginProcesses.otherJvm(tmp, recorder, jar, classMode = false),
            LoginProcesses.otherJvm(tmp, recorder, jar, classMode = true),
        )
        try {
            Thread.sleep(500)
            val env = mapOf("SPLICE_JAR" to jar.toString())
            val ran = run("bash", hook.toString(), stdin = """{"prompt":"/login"}""", dir = tmp, env = env)
            val reason = decision(ran)
            val restarted = "A previous Codex (ChatGPT) sign-in was still waiting and was cancelled."
            assertTrue(reason.startsWith(restarted), reason)
            assertTrue(pending.waitFor(5, TimeUnit.SECONDS), "the pending sign-in was killed")
            assertEquals(listOf("login"), recordedArgs(tmp), "and a fresh one was started")
            bystanders.forEachIndexed { i, b -> assertTrue(b.isAlive, "bystander $i survives: not our invocation") }
        } finally {
            bystanders.forEach { it.destroyForcibly() }
            pending.destroyForcibly()
        }
    }

    /** The replacement must be resolvable BEFORE a waiting sign-in is cancelled, and must survive
     *  its first moment after: otherwise the hook destroys a working sign-in and starts nothing. */
    @Test
    fun `a login command that cannot start leaves a pending sign-in alone and says so - review 2026-09-14`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val missing = tmp.resolve("no-such-wrapper")
        val hook = write(tmp, "login-missing.sh", LoginHookScripts.loginHookScript(browserSpec(missing)))
        val jar = LoginProcesses.parkJar(tmp)
        val pending = LoginProcesses.pendingLogin(missing, jar, ignoreTerm = false)
        try {
            Thread.sleep(500)
            val env = mapOf("SPLICE_JAR" to jar.toString())
            val ran = run("bash", hook.toString(), stdin = """{"prompt":"/login"}""", dir = tmp, env = env)
            val reason = decision(ran)
            assertTrue(reason.startsWith("The Codex (ChatGPT) login command ($missing) is not on"), reason)
            Thread.sleep(300)
            assertTrue(pending.isAlive, "the waiting sign-in was left alone: nothing could replace it")
        } finally {
            pending.destroyForcibly()
        }
        val dying = write(tmp, "dying.sh", "#!/usr/bin/env bash\nexit 3\n")
        dying.toFile().setExecutable(true)
        val hook2 = write(tmp, "login-dying.sh", LoginHookScripts.loginHookScript(browserSpec(dying)))
        val ran2 = run("bash", hook2.toString(), stdin = """{"prompt":"/login"}""", dir = tmp)
        val reason2 = decision(ran2)
        assertTrue(reason2.startsWith("$dying login exited as soon as it started"), reason2)
    }

    /** The hook owns only the sign-ins it started. One the user began in a terminal (`claudex login
     *  --label work`, waiting on the browser) is the same argv; it is named and left alone. */
    @Test
    fun `a sign-in the user started in a terminal is named and left alone - review 2026-09-14`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val recorder = recorder(tmp)
        val hook = write(tmp, "login-foreign.sh", LoginHookScripts.loginHookScript(browserSpec(recorder)))
        val jar = LoginProcesses.parkJar(tmp)
        val terminal = LoginProcesses.pendingLogin(recorder, jar, ignoreTerm = false, hookStarted = false)
        try {
            Thread.sleep(500)
            val env = mapOf("SPLICE_JAR" to jar.toString())
            val ran = run("bash", hook.toString(), stdin = """{"prompt":"/login --label work"}""", dir = tmp, env = env)
            val reason = decision(ran)
            assertTrue(reason.startsWith("A Codex (ChatGPT) sign-in started outside this session"), reason)
            Thread.sleep(300)
            assertTrue(terminal.isAlive, "the terminal sign-in was signalled")
            assertTrue(!Files.exists(tmp.resolve("args.txt")), "nothing was started")
        } finally {
            terminal.destroyForcibly()
        }
    }

    @Test
    fun `a sign-in spelled with the head key is found under either origin - review 2026-09-14`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val recorder = recorder(tmp)
        val hook = write(tmp, "login-key.sh", LoginHookScripts.loginHookScript(browserSpec(recorder)))
        val jar = LoginProcesses.parkJar(tmp)
        val env = mapOf("SPLICE_JAR" to jar.toString())
        // `splice login codex` in a terminal: named and left alone, nothing started.
        val terminal =
            LoginProcesses.pendingLogin(recorder, jar, ignoreTerm = false, hookStarted = false, word = "codex")
        try {
            Thread.sleep(500)
            val reason = decision(run("bash", hook.toString(), stdin = """{"prompt":"/login"}""", dir = tmp, env = env))
            assertTrue(reason.startsWith("A Codex (ChatGPT) sign-in started outside this session"), reason)
            Thread.sleep(300)
            assertTrue(terminal.isAlive, "the terminal sign-in was signalled")
            assertTrue(!Files.exists(tmp.resolve("args.txt")), "nothing was started")
        } finally {
            terminal.destroyForcibly()
            terminal.waitFor(5, TimeUnit.SECONDS)
        }
        // The same spelling started by a hook: cancelled, and a fresh sign-in starts.
        val pending = LoginProcesses.pendingLogin(recorder, jar, ignoreTerm = false, word = "codex")
        try {
            Thread.sleep(500)
            val reason = decision(run("bash", hook.toString(), stdin = """{"prompt":"/login"}""", dir = tmp, env = env))
            val cancelled = "A previous Codex (ChatGPT) sign-in was still waiting and was cancelled."
            assertTrue(reason.startsWith(cancelled), reason)
            assertTrue(pending.waitFor(5, TimeUnit.SECONDS), "the pending sign-in was killed")
            assertEquals(listOf("login"), recordedArgs(tmp), "and a fresh one was started")
        } finally {
            pending.destroyForcibly()
        }
    }

    @Test
    fun `a bystander carrying the text, with no pending sign-in, is neither killed nor a restart - V4-13`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val recorder = recorder(tmp)
        val hook = write(tmp, "login-bystander.sh", LoginHookScripts.loginHookScript(browserSpec(recorder)))
        val jar = LoginProcesses.parkJar(tmp)
        val bystanders = listOf(
            LoginProcesses.bystander(recorder, oneWord = false, argv0Java = true),
            LoginProcesses.otherJvm(tmp, recorder, jar, classMode = false),
            LoginProcesses.otherJvm(tmp, recorder, jar, classMode = true),
        )
        try {
            Thread.sleep(500)
            val env = mapOf("SPLICE_JAR" to jar.toString())
            val ran = run("bash", hook.toString(), stdin = """{"prompt":"/login"}""", dir = tmp, env = env)
            assertTrue(decision(ran).startsWith("Opening your browser"), "no restart was announced: ${ran.out}")
            assertEquals(listOf("login"), recordedArgs(tmp))
            bystanders.forEach { assertTrue(it.isAlive, "a bystander was signalled") }
        } finally {
            bystanders.forEach { it.destroyForcibly() }
        }
    }

    @Test
    fun `a pending sign-in that ignores TERM is killed, and only then does the new one start - V4-13`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val recorder = recorder(tmp)
        val hook = write(tmp, "login-stuck.sh", LoginHookScripts.loginHookScript(browserSpec(recorder)))
        val jar = LoginProcesses.parkJar(tmp)
        val pending = LoginProcesses.pendingLogin(recorder, jar, ignoreTerm = true)
        try {
            Thread.sleep(500)
            val env = mapOf("SPLICE_JAR" to jar.toString())
            val ran = run("bash", hook.toString(), stdin = """{"prompt":"/login"}""", dir = tmp, env = env)
            val reason = decision(ran)
            val restarted = "A previous Codex (ChatGPT) sign-in was still waiting and was cancelled."
            assertTrue(reason.startsWith(restarted), reason)
            assertTrue(pending.waitFor(5, TimeUnit.SECONDS), "the pending sign-in was killed after TERM was ignored")
            assertEquals(listOf("login"), recordedArgs(tmp), "and a fresh one was started")
        } finally {
            pending.destroyForcibly()
        }
    }

    /** The unparsed refusal is every head's: an api-key head used to answer an unreadable input
     *  with its paste/terminal lead text, as if a bare /login had been read. */
    @Test
    fun `an api-key head refuses an input without a readable prompt with the unparsed text - review 2026-09-14`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val hook = write(tmp, "login-apikey-unparsed.sh", LoginHookScripts.loginHookScript(spec()))
        val ran = run("bash", hook.toString(), stdin = """{"prompt":null,"note":"/login"}""", dir = tmp)
        val reason = decision(ran)
        assertTrue(reason.startsWith("/login was seen but this hook input carries no prompt string"), reason)
        val readable = run("bash", hook.toString(), stdin = """{"prompt":"/login"}""", dir = tmp)
        assertTrue(decision(readable).startsWith("Paste your"), "a readable /login still gets the lead text")
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

    // DR-137: `find` defaults to -P, so the freshness check read the LINK's own mtime while every
    // other reader of the same receipt follows the link — `[ -f ]` and `cat` in this very script,
    // and Files.isRegularFile / Files.getLastModifiedTime in LoginOutcomeFile.consume. A symlink
    // created now over a long-dead receipt therefore announced a stale "sign-in failed" as if it
    // were fresh. Exactly the drift DR-103's comment ("one definition, or the two readers drift")
    // exists to prevent. Low severity — SecureFile.writeAtomic0600 always lands a regular file —
    // but the two readers must agree about which file they are judging.
    @Test
    fun `a symlink receipt is judged by its target's age, like every other reader - DR-137`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val target = tmp.resolve("dr137-target.txt")
        Files.write(target, "sign-in failed: expired device code".toByteArray())
        Files.setLastModifiedTime(target, FileTime.fromMillis(0L)) // far outside the freshness window
        val link = tmp.resolve("dr137-link.txt")
        Files.createSymbolicLink(link, target) // the LINK's own mtime is NOW
        write(tmp, "login-dr137.sh", LoginHookScripts.loginHookScript(spec(link.toString())))
        val ran = run("bash", "login-dr137.sh", stdin = """{"prompt":"hello"}""", dir = tmp)
        assertEquals(0, ran.exit, ran.err)
        assertTrue(
            !ran.out.contains("sign-in failed"),
            "a receipt whose TARGET is stale must not be announced as fresh: ${ran.out}",
        )
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

// DR-103: the generated hook is the PRODUCTION consumer of the login receipt, and it enforced no
// age — the 10-minute freshness contract lived only in LoginOutcomeFile.consume, which nothing in
// production calls. A stale failure receipt from days ago announced "sign-in did not complete" on
// a fresh session long after auth was fixed by another path. These run the real script.
class LoginHookReceiptAgeTest {

    private val tmp: Path = Files.createTempDirectory("login-hook-age")

    private fun freshSpec(outcomeFile: String) = LoginHookSpec(
        loginCommand = "claude-splice login",
        signInLabel = "OpenRouter",
        viaBrowser = false,
        sentinel = "SPLICE_CODEX_LOGIN",
        outcomeFile = outcomeFile,
        canCapturePaste = true,
    )

    @Test
    fun `a fresh receipt announces once and is consumed - control`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val receipt = tmp.resolve("receipt-fresh.txt")
        Files.writeString(receipt, "sign-in complete\n")
        val hook = write(tmp, "login-fresh.sh", LoginHookScripts.loginHookScript(freshSpec(receipt.toString())))
        val ran = run("bash", hook.toString(), stdin = """{"prompt":"hello"}""", dir = tmp)
        assertEquals(0, ran.exit, ran.err)
        assertTrue(ran.out.contains("sign-in complete"), "a fresh receipt must announce; out=${ran.out}")
        assertTrue(!Files.exists(receipt), "the receipt is consumed on announce")
    }

    @Test
    fun `a stale receipt is consumed silently, never announced - DR-103`() {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val receipt = tmp.resolve("receipt-stale.txt")
        Files.writeString(receipt, "sign-in did not complete\n")
        val elevenMinutesAgo = System.currentTimeMillis() - 11 * 60 * 1000
        Files.setLastModifiedTime(receipt, java.nio.file.attribute.FileTime.fromMillis(elevenMinutesAgo))
        val hook = write(tmp, "login-stale.sh", LoginHookScripts.loginHookScript(freshSpec(receipt.toString())))
        val ran = run("bash", hook.toString(), stdin = """{"prompt":"hello"}""", dir = tmp)
        assertEquals(0, ran.exit, ran.err)
        assertTrue(
            !ran.out.contains("sign-in did not complete"),
            "a receipt older than the freshness window must not announce; out=${ran.out}",
        )
        assertTrue(!Files.exists(receipt), "a stale receipt is still consumed so it cannot linger")
    }
}
