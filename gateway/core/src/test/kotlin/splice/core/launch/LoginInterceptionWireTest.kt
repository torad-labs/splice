// DR-8: wire() used to wrap its ENTIRE hook-materialization body in one
// runCatchingCancellable{}.getOrElse{emptyMap()}, so a single IOException anywhere silently
// dropped ALL hook entries — including the token-capture hook whose stated job is to stop a
// pasted API key from reaching the model. Three materially different causes (commands dir
// unwritable, capture hook unwritable, no login command configured) produced an identical,
// unlogged empty map, and the head launched uninterceptable.
//
// The contract these tests pin: the CAPTURE leg is fail-closed (a head that cannot install the
// hook that intercepts a pasted credential must not launch — its failure propagates out of
// materialize() and fails the launch), while the /login-interceptor leg stays best-effort but
// LOGS its cause, so "no /login interception" is a greppable daemon-log line instead of a
// silence. Every obstruction here is the concrete one from the ledger item: a pre-existing
// regular file (or directory) where wire() needs to create the opposite kind.
package splice.core.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class LoginInterceptionWireTest {

    private val capture = TokenCaptureSpec(
        envVar = "OPENROUTER_API_KEY",
        tokenPattern = "sk-or-[A-Za-z0-9_-]{20,}",
        providerLabel = "OpenRouter",
    )

    private fun wire(
        configDir: Path,
        loginCommand: String,
        tokenCapture: TokenCaptureSpec?,
        log: MutableList<String>,
    ) = LoginInterception.wire(
        configDir = configDir,
        loginCommand = loginCommand,
        signInLabel = "OpenRouter",
        globalCommands = null,
        viaBrowser = false,
        tokenCapture = tokenCapture,
        log = LogSink { log += it },
    )

    @Test
    fun `an obstructed commands dir skips login interception with a logged cause but keeps the capture hook`(
        @TempDir tmp: Path,
    ) {
        Files.writeString(tmp.resolve("commands"), "not a dir") // createDirectories will throw
        val log = mutableListOf<String>()

        val hooks = wire(tmp, loginCommand = "openrouter login", tokenCapture = capture, log = log)

        val ups = hooks["UserPromptSubmit"].orEmpty()
        assertEquals(1, ups.size, "the capture hook must survive a login-leg failure, got $hooks")
        assertTrue(
            ups.single().toString().contains("splice-key-capture-hook.sh"),
            "the surviving entry must be the capture hook: $ups",
        )
        assertTrue(
            log.any { it.contains("/login interception") && it.contains("commands") },
            "the skipped login leg must log its cause, got $log",
        )
    }

    @Test
    fun `a capture-hook write failure propagates instead of launching an uninterceptable head`(
        @TempDir tmp: Path,
    ) {
        Files.createDirectories(tmp.resolve("splice-key-capture-hook.sh")) // writeString will throw
        val log = mutableListOf<String>()

        assertThrows<IOException> {
            wire(tmp, loginCommand = "openrouter login", tokenCapture = capture, log = log)
        }
    }

    @Test
    fun `a login-only head with an obstructed commands dir returns no hooks but logs the cause`(
        @TempDir tmp: Path,
    ) {
        Files.writeString(tmp.resolve("commands"), "not a dir")
        val log = mutableListOf<String>()

        val hooks = wire(tmp, loginCommand = "grok login", tokenCapture = null, log = log)

        assertTrue(hooks.isEmpty(), "nothing installable, so no entries: $hooks")
        assertTrue(
            log.any { it.contains("/login interception") },
            "an empty result from a non-empty spec must be logged, got $log",
        )
    }

    @Test
    fun `the intact path installs both hooks and the login command`(@TempDir tmp: Path) {
        val log = mutableListOf<String>()

        val hooks = wire(tmp, loginCommand = "openrouter login", tokenCapture = capture, log = log)

        assertEquals(2, hooks["UserPromptSubmit"].orEmpty().size, "login + capture: $hooks")
        assertTrue(Files.isRegularFile(tmp.resolve("commands").resolve("login.md")))
        assertTrue(log.isEmpty(), "a clean wire must not log, got $log")
    }

    // DR-8 redo (codex-splice review, 2026-08-30): the fail-closed capture leg was only fail-closed
    // against a WRITE failure. writeHookScript caught the chmod separately and merely logged it, so a
    // capture hook could be written 0644, registered in settings.json, and never execute — the head
    // comes up uninterceptable exactly as before, with a log line nobody reads standing in for a
    // failed launch. The chmod is what makes the hook a hook, so for THIS leg it is fatal too.
    @Test
    fun `a capture hook that cannot be made executable fails the launch`(@TempDir tmp: Path) {
        val failure = assertThrows<IOException> {
            LoginInterception.wire(
                configDir = tmp,
                loginCommand = "",
                signInLabel = "OpenRouter",
                globalCommands = null,
                viaBrowser = false,
                tokenCapture = capture,
                log = LogSink { },
                chmod = { _, _ -> throw IOException("injected chmod failure") },
            )
        }
        assertTrue(
            failure.message.orEmpty().contains("executable"),
            "the launch failure must name the un-executable hook, got ${failure.message}",
        )
    }

    @Test
    fun `the login leg stays best-effort when its own chmod fails`(@TempDir tmp: Path) {
        val log = mutableListOf<String>()

        val hooks = LoginInterception.wire(
            configDir = tmp,
            loginCommand = "openrouter login",
            signInLabel = "OpenRouter",
            globalCommands = null,
            viaBrowser = false,
            tokenCapture = null,
            log = LogSink { log += it },
            chmod = { _, _ -> throw IOException("injected chmod failure") },
        )

        assertTrue(hooks.isEmpty(), "the login hook must be dropped, not registered unexecutable: $hooks")
        assertTrue(log.any { it.contains("NOT installed") }, "the dropped leg must log its cause, got $log")
    }

    @Test
    fun `a blocked advertiser script degrades to no advertiser with a logged cause`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("splice-keysetup-hook.sh"))
        val log = mutableListOf<String>()

        val hooks = LoginInterception.keySetupAdvertiser(
            configDir = tmp,
            spec = capture,
            loginCommand = "openrouter login",
            log = LogSink { log += it },
        )

        assertTrue(hooks.isEmpty())
        assertTrue(
            log.any { it.contains("advertiser") },
            "the dropped advertiser must log its cause, got $log",
        )
    }
}
