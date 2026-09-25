// NEW: V4-227 — the paste-a-key hook stores the key and stops. It ran `nohup splice restart &` after
// `splice key set --stdin`, which dropped every head's turns in flight to deliver a key the head reads
// on its next request anyway. Run for real: the generated script under bash, with a `splice` on PATH
// that records each call, so a restart the hook starts in the background is seen, not inferred.
package splice.client.login

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// why: the old hook's background restart lands within milliseconds; a second is the window in which a
// call that is coming would have been recorded.
private const val SETTLE_MS = 1_000L
private const val POLL_MS = 25L
private const val TOKEN = "sk-or-AAAAAAAAAAAAAAAAAAAAAAAA"

class KeyCaptureNoRestartTest {

    private fun bashAvailable(): Boolean = runCatching {
        ProcessBuilder("bash", "-c", "exit 0").start().waitFor(10, TimeUnit.SECONDS)
    }.getOrDefault(false)

    /** A `splice` that drains stdin for `--stdin` and appends its arguments, one call per line. */
    private fun fakeSplice(bin: Path, calls: Path) {
        val body = "#!/usr/bin/env bash\n" +
            "if [[ \" \$* \" == *' --stdin '* ]]; then cat >/dev/null; fi\n" +
            "printf '%s\\n' \"\$*\" >> '$calls'\n"
        Files.createDirectories(bin)
        Files.writeString(bin.resolve("splice"), body).toFile().setExecutable(true)
    }

    private fun callsAfterSettle(calls: Path): List<String> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SETTLE_MS)
        while (System.nanoTime() < deadline) {
            if (Files.exists(calls) && Files.readAllLines(calls).any { it.startsWith("restart") }) break
            Thread.sleep(POLL_MS)
        }
        return if (Files.exists(calls)) Files.readAllLines(calls) else emptyList()
    }

    /** RED before V4-227: the recorded calls were [key set OPENROUTER_API_KEY --stdin, restart]. */
    @Test
    fun `a pasted key is stored and nothing restarts`(@TempDir tmp: Path) {
        assumeTrue(bashAvailable(), "bash is required to execute the generated hook")
        val calls = tmp.resolve("calls.log")
        fakeSplice(tmp.resolve("bin"), calls)
        val hook = tmp.resolve("capture.sh")
        val capture = TokenCaptureSpec("OPENROUTER_API_KEY", "sk-or-[A-Za-z0-9_-]{20,}", "OpenRouter")
        Files.writeString(hook, LoginHookScripts.captureHookScript(capture))

        val process = ProcessBuilder("bash", hook.toString()).directory(tmp.toFile()).apply {
            environment()["PATH"] = "${tmp.resolve("bin")}:${System.getenv("PATH")}"
            environment()["HOME"] = tmp.toString()
        }.start()
        process.outputStream.use { it.write("""{"prompt":"$TOKEN"}""".toByteArray()) }
        val out = process.inputStream.readBytes().decodeToString()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the hook did not exit")

        assertEquals(listOf("key set OPENROUTER_API_KEY --stdin"), callsAfterSettle(calls))
        val reason = Json.parseToJsonElement(out).jsonObject["reason"]!!.jsonPrimitive.content
        assertTrue(reason.contains("the next request uses it, no restart"), reason)
        assertTrue(!reason.contains("restarting"), reason)
    }
}
