// NEW: Accounts invokes the real Claude CLI, never the terminal-replacing splice login shim.
package splice.app.auth.claude

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.client.wrap.WrapStateRead
import splice.core.util.Cancellables
import java.io.Closeable
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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

/** Announces only a validated native browser authorization URL, never arbitrary process output. */
internal fun interface NativeAuthAnnouncement {
    fun browser(url: String)
}

/** Injected process creation keeps tests off real credentials and providers. */
internal fun interface NativeAuthStart {
    fun start(builder: ProcessBuilder): Process
}

internal class NativeClaudeAuth(
    private val wrap: WrapStateRead,
    private val environment: Map<String, String>,
    private val dispatcher: CoroutineDispatcher,
    private val start: NativeAuthStart = NativeAuthStart(ProcessBuilder::start),
) {
    /** Why no sign-in may start now: the shim stands in for `claude` and the wrap record is unusable, so
     *  [begin] would run bare `claude`, which is the shim again. Callers ask first and fail with this text. */
    fun refusal(): String? = wrap.refusal()

    /** The sign-in that REPLACES one command's own login, in that command's own config dir. */
    fun begin(location: ClaudeLoginLocation): NativeClaudeAuthRun =
        begin(location.target.head.configDir.takeIf { location.id == ClaudeLoginPlaceId.SPLICE })

    /** One native sign-in, writing [configDir], or the caller's own `~/.claude` when it is null. An added account
     *  always names its PENDING folder here, so a sign-in in flight can never write a login already filed. */
    fun begin(configDir: Path?): NativeClaudeAuthRun {
        val builder = ProcessBuilder(wrap.realBinaryPath() ?: "claude", "auth", "login", "--claudeai")
            .redirectErrorStream(true)
        val env = builder.environment()
        env.putAll(environment)
        env.keys.removeIf { name ->
            name.startsWith("ANTHROPIC_") || name.startsWith("CLAUDE_CODE_USE_") ||
                name == "CLAUDE_CODE_OAUTH_TOKEN" || name == "CLAUDE_CONFIG_DIR"
        }
        if (configDir != null) env["CLAUDE_CONFIG_DIR"] = configDir.toString()
        return NativeClaudeAuthRun(start.start(builder), dispatcher)
    }
}

/** Owns one child and its open fallback-code stdin. Cancellation stops it before the destination is released. */
internal class NativeClaudeAuthRun(
    private val process: Process,
    private val dispatcher: CoroutineDispatcher,
) : Closeable {
    val stopped: Boolean get() = !process.isAlive

    suspend fun await(announcement: NativeAuthAnnouncement): Boolean = coroutineScope {
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
        exit == 0
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
            if (authorization(candidate) != null) announcement.browser(candidate)
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
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                check(process.waitFor(1, TimeUnit.SECONDS)) { "native login child did not stop" }
            }
            process.inputStream.close()
        }
    }
}
