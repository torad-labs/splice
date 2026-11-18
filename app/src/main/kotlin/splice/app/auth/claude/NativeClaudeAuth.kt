// NEW: Accounts invokes the real Claude CLI, never the terminal-replacing splice login shim.
package splice.app.auth.claude

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import splice.client.wrap.ClaudeToRun
import splice.client.wrap.WrapStateRead
import splice.core.util.Cancellables
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessTicker
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// why: an OAuth authorization URL fits in 16 KiB; a runaway child cannot grow the retained scan buffer.
private const val AUTH_SCAN_CHARS = 16 * 1024

// why: fixed 2 KiB reads bound the allocation peak independently of a child's line length.
private const val AUTH_READ_CHARS = 2048

// why: the pasted native authorization code is small; 4 KiB rejects unbounded management submissions.
private const val AUTH_CODE_CHARS = 4096

// why: the browser sign-in each Anthropic host serves, by host, so a path is only accepted on its own host. Claude Code
// 2.1.289 prints https://claude.com/cai/oauth/authorize (a scratch run, Oct 3); earlier builds printed claude.ai's.
private val AUTHORIZE_PATHS: Map<String, String> = mapOf(
    "claude.com" to "/cai/oauth/authorize",
    "claude.ai" to "/oauth/authorize",
    "console.anthropic.com" to "/oauth/authorize",
    "platform.claude.com" to "/oauth/authorize",
)

// why: how often a sign-in looks for the link Claude Code handed its browser; it is there within milliseconds of the
// start, so the console sees it on its first read.
private const val OPENED_POLL_MS = 100L

// why: the program splice names as BROWSER. Claude Code runs it with its sign-in link as the one argument, and it
// writes that link beside itself for splice to read, opening nothing.
private const val BROWSER_CATCH_SCRIPT = "#!/bin/sh\nprintf '%s\\n' \"\$1\" >> \"\$(dirname \"\$0\")/opened\"\n"

/** A sign-in link to show the person: [pasteBack] when its page ends in a code to paste back here. */
internal data class NativeAuthLink(val url: String, val pasteBack: Boolean)

/** Announces only a validated native browser authorization URL, never arbitrary process output. */
internal fun interface NativeAuthAnnouncement {
    fun browser(link: NativeAuthLink)
}

/** The browser splice gives one Claude Code sign-in, in a folder of its own that [close] removes. Claude Code offers
 *  two links: the one it hands its browser returns to its own listener on localhost and finishes by itself, and the
 *  one it prints ends on a page with a code to paste back. Left with the daemon's browser, it opened the first on the
 *  desktop when it could while the console showed the second, so a Claude sign-in asked for a code only sometimes
 *  (operator, Nov 17, 2026). Caught here, the first is the one the console shows. */
internal class NativeBrowserCatch : Closeable {
    private val folder: Path = Files.createTempDirectory("splice-claude-signin-")
    private val opened: Path = folder.resolve("opened")

    /** What BROWSER names. */
    val program: Path = folder.resolve("browser")

    init {
        Files.writeString(program, BROWSER_CATCH_SCRIPT)
        check(program.toFile().setExecutable(true, true)) { "the sign-in's browser catch could not be made runnable" }
    }

    /** Every link Claude Code has handed its browser so far. */
    fun links(): List<String> = if (Files.exists(opened)) Files.readAllLines(opened) else emptyList()

    override fun close() {
        Files.deleteIfExists(opened)
        Files.deleteIfExists(program)
        Files.deleteIfExists(folder)
    }
}

/** Injected process creation keeps tests off real credentials and providers. */
internal fun interface NativeAuthStart {
    fun start(builder: ProcessBuilder): Process
}

/** What starting a native sign-in came to: a child running, or a refusal to say before anything started. */
internal sealed class NativeSignIn {
    class Running(val run: NativeClaudeAuthRun) : NativeSignIn()

    class Refused(val reason: String) : NativeSignIn()
}

internal class NativeClaudeAuth(
    private val wrap: WrapStateRead,
    private val environment: Map<String, String>,
    private val dispatcher: CoroutineDispatcher,
    private val start: NativeAuthStart = NativeAuthStart(ProcessBuilder::start),
) {
    /** One native sign-in, writing [configDir] and nothing else. Every sign-in names its PENDING folder here, so a
     *  sign-in in flight can never write a login already filed, nor the one a running session reads. */
    fun begin(configDir: Path): NativeSignIn {
        // ONE read, acted on here: a wrap record that cannot name the real claude while the shim stands in for it
        // ends the sign-in, since bare `claude` would run the shim again.
        val claude = when (val chosen = wrap.claude()) {
            is ClaudeToRun.Refused -> return NativeSignIn.Refused(chosen.reason)
            is ClaudeToRun.Wrapped -> chosen.path
            ClaudeToRun.ThroughPath -> "claude"
        }
        val builder = ProcessBuilder(claude, "auth", "login", "--claudeai")
            .redirectErrorStream(true)
        val env = builder.environment()
        env.putAll(environment)
        env.keys.removeIf { name ->
            name.startsWith("ANTHROPIC_") || name.startsWith("CLAUDE_CODE_USE_") ||
                name == "CLAUDE_CODE_OAUTH_TOKEN" || name == "CLAUDE_CONFIG_DIR"
        }
        env["CLAUDE_CONFIG_DIR"] = configDir.toString()
        val browser = NativeBrowserCatch()
        env["BROWSER"] = browser.program.toString()
        val process = try {
            start.start(builder)
        } catch (e: IOException) {
            browser.close()
            throw e
        }
        return NativeSignIn.Running(NativeClaudeAuthRun(process, dispatcher, browser))
    }
}

/** Owns one child and its open fallback-code stdin. Cancellation stops it before the destination is released. */
internal class NativeClaudeAuthRun(
    private val process: Process,
    private val dispatcher: CoroutineDispatcher,
    private val browser: NativeBrowserCatch,
) : Closeable {
    /** Set once the link that finishes by itself is shown; the printed code link is not shown after it. */
    private val finishesItself = AtomicBoolean(false)

    private val ticker: Ticker = ProcessTicker()

    suspend fun await(announcement: NativeAuthAnnouncement): Boolean = coroutineScope {
        val opened = launch(dispatcher) { watchOpened(announcement) }
        val output = launch(dispatcher) { readOutput(announcement) }
        val exit = suspendCancellableCoroutine { continuation ->
            process.onExit().whenComplete { ended, failure ->
                if (failure != null) {
                    continuation.resumeWithException(failure)
                } else {
                    continuation.resume(ended.exitValue())
                }
            }
            continuation.invokeOnCancellation { stopQuietly() }
        }
        output.join()
        opened.cancel()
        exit == 0
    }

    /** Shows the link Claude Code handed its browser, the one that finishes by itself, as soon as it is there. */
    private suspend fun watchOpened(announcement: NativeAuthAnnouncement) {
        do {
            opened(announcement)
        } while (!finishesItself.get() && ticker.awaitTick(OPENED_POLL_MS))
    }

    /** Shows the link Claude Code has handed its browser, once, if it has handed one. */
    @Synchronized
    private fun opened(announcement: NativeAuthAnnouncement) {
        if (finishesItself.get()) return
        val url = browser.links().firstOrNull { authorization(it) != null && returnsHere(it) } ?: return
        finishesItself.set(true)
        announcement.browser(NativeAuthLink(url, pasteBack = false))
    }

    /** The printed link, whose page ends in a code to paste back: shown only while Claude Code has opened no other,
     *  as a Claude Code that ignored BROWSER would. It opens its link before it prints this one, so that link is
     *  looked for first. */
    @Synchronized
    private fun printed(url: String, announcement: NativeAuthAnnouncement) {
        opened(announcement)
        if (!finishesItself.get()) announcement.browser(NativeAuthLink(url, pasteBack = true))
    }

    /** Whether the sign-in [url] returns to Claude Code's own listener on this machine. */
    private fun returnsHere(url: String): Boolean {
        val redirect = URI(url).rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("redirect_uri=") }
            ?.substringAfter('=') ?: return false
        return try {
            val target = URI(URLDecoder.decode(redirect, Charsets.UTF_8))
            target.scheme == "http" && (target.host == "localhost" || target.host == "127.0.0.1")
        } catch (_: IllegalArgumentException) {
            false // A redirect that does not decode is not a link back to this machine.
        } catch (_: java.net.URISyntaxException) {
            false // Likewise one that does not parse.
        }
    }

    private fun readOutput(announcement: NativeAuthAnnouncement) {
        val buffer = CharArray(AUTH_READ_CHARS)
        val scan = StringBuilder()
        process.inputStream.reader().use { reader ->
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) {
                    scan.append('\n')
                    announce(scan, announcement)
                    break
                }
                scan.append(buffer, 0, read)
                announce(scan, announcement)
                if (scan.length > AUTH_SCAN_CHARS) scan.delete(0, scan.length - AUTH_SCAN_CHARS)
            }
        }
    }

    @Synchronized
    fun submit(code: String): Boolean {
        if (!process.isAlive || !validCode(code)) return false
        process.outputStream.write((code + "\n").toByteArray(Charsets.UTF_8))
        process.outputStream.flush()
        return true
    }

    private fun validCode(code: String): Boolean =
        code.isNotBlank() && code.length <= AUTH_CODE_CHARS && code.none { it == '\n' || it == '\r' }

    private fun announce(scan: StringBuilder, announcement: NativeAuthAnnouncement) {
        while (true) {
            val text = scan.toString()
            val beginning = text.indexOf("https://")
            if (beginning < 0) return
            val end = indexOfFirstFrom(text, beginning)
            if (end < 0) return
            val candidate = text.substring(beginning, end)
            scan.delete(0, end)
            if (authorization(candidate) != null && !returnsHere(candidate)) printed(candidate, announcement)
        }
    }

    private fun authorization(candidate: String): URI? = try {
        URI(candidate).takeIf { url ->
            AUTHORIZE_PATHS[url.host] == url.path && url.scheme == "https" && url.userInfo == null && url.port == -1
        }
    } catch (_: java.net.URISyntaxException) {
        null // Malformed child text is classified as not an authorization announcement.
    }

    private fun indexOfFirstFrom(text: String, start: Int): Int {
        for (index in start until text.length) if (text[index].isWhitespace() || text[index] == '\u001b') return index
        return -1
    }

    private fun stopQuietly() {
        Cancellables.discard(
            Cancellables.runCatchingBestEffort { process.destroyForcibly() },
            "the cancellation callback cannot throw; lifecycle close retries and verifies child exit",
        )
    }

    override fun close() {
        try {
            process.outputStream.close()
        } finally {
            browser.close()
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                check(process.waitFor(1, TimeUnit.SECONDS)) { "native login child did not stop" }
            }
            process.inputStream.close()
        }
    }
}
