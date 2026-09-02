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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

class LoginInterceptionWireTest {

    private val capture = TokenCaptureSpec(
        envVar = "OPENROUTER_API_KEY",
        tokenPattern = "sk-or-[A-Za-z0-9_-]{20,}",
        providerLabel = "OpenRouter",
    )

    // A probe that always reports the dir CAN execute, so a write-stage fault test lands in
    // writeHookScript instead of short-circuiting at the exec probe (which shares the chmod seam a
    // chmod-failure test injects and would otherwise fail first — DR-8 redo-3 / codex catch).
    private val passProbe = HookExecProbe { _, _ -> null }

    // The stage is a unique createTempFile now ("$name.<random>.tmp"), so "no stranded stage" is a
    // glob over the dir, not a fixed-name existence check that a random name passes vacuously.
    private fun strayFiles(dir: Path, glob: String): List<String> =
        Files.newDirectoryStream(dir, glob).use { stream -> stream.map { it.fileName.toString() } }

    private fun wire(
        configDir: Path,
        loginCommand: String,
        tokenCapture: TokenCaptureSpec?,
        log: MutableList<String>,
        execProbe: HookExecProbe? = null,
    ) = if (execProbe == null) {
        // Existing arms run the REAL default probe, so every green arm also proves the probe does
        // not false-positive on an ordinary executable temp dir.
        LoginInterception.wire(
            configDir = configDir,
            loginCommand = loginCommand,
            signInLabel = "OpenRouter",
            globalCommands = null,
            viaBrowser = false,
            tokenCapture = tokenCapture,
            log = LogSink { log += it },
        )
    } else {
        LoginInterception.wire(
            configDir = configDir,
            loginCommand = loginCommand,
            signInLabel = "OpenRouter",
            globalCommands = null,
            viaBrowser = false,
            tokenCapture = tokenCapture,
            log = LogSink { log += it },
            execProbe = execProbe,
        )
    }

    // DR-8 redo-2 (codex noexec catch): chmod(0700) succeeds on a noexec mount while exec fails
    // EACCES, so the landed chmod-outcome check accepted a capture hook that could never run. The
    // probe seam makes the counterexample deterministic (a real noexec mount needs root); the
    // injected failure is byte-for-byte the ProcessBuilder EACCES shape from codex's /run/lock repro.
    @Test
    fun `a noexec directory fails the launch when a capture hook is required`(@TempDir tmp: Path) {
        val log = mutableListOf<String>()
        val noexec = HookExecProbe { _, _ ->
            IOException("Cannot run program: error=13, Permission denied")
        }

        val error = assertThrows<IOException> {
            wire(tmp, loginCommand = "openrouter login", tokenCapture = capture, log = log, execProbe = noexec)
        }

        assertTrue(error.message.orEmpty().contains("cannot execute"), "got: ${error.message}")
        assertFalse(
            Files.exists(tmp.resolve("splice-key-capture-hook.sh")),
            "nothing may be staged or published in a directory that cannot execute it",
        )
    }

    @Test
    fun `a noexec directory degrades loudly and registers nothing without a capture spec`(@TempDir tmp: Path) {
        val log = mutableListOf<String>()
        val noexec = HookExecProbe { _, _ ->
            IOException("Cannot run program: error=13, Permission denied")
        }

        val hooks = wire(tmp, loginCommand = "openrouter login", tokenCapture = null, log = log, execProbe = noexec)

        assertTrue(hooks.isEmpty(), "known-unrunnable hooks must not register, got $hooks")
        assertTrue(
            log.any { it.contains("cannot execute scripts") },
            "the degraded head must name its cause in the log, got $log",
        )
        assertFalse(Files.exists(tmp.resolve("splice-login-hook.sh")), "nothing may be staged")
    }

    @Test
    fun `the real exec probe leaves no residue beside the hooks`(@TempDir tmp: Path) {
        val log = mutableListOf<String>()

        val hooks = wire(tmp, loginCommand = "openrouter login", tokenCapture = capture, log = log)

        assertTrue(hooks.isNotEmpty(), "an ordinary dir must wire hooks, got $hooks")
        assertTrue(
            strayFiles(tmp, ".splice-exec-probe.*").isEmpty(),
            "the probe file must be deleted after the exec attempt",
        )
    }

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
                execProbe = passProbe,
            )
        }
        assertTrue(
            failure.message.orEmpty().contains("chmod"),
            "the launch failure must name the failed chmod, got ${failure.message}",
        )
    }

    // DR-8 redo + DR-31 stage-and-swap. The DR-8 shape: the target PRE-EXISTS 0777 (a leftover, or
    // seeded by anything that can write the config dir), the chmod fails, and the old isExecutable
    // probe called the 0777 file installed. The DR-31 correction of the correction: DELETING the
    // target on failure was also wrong — writeString had already truncated a possibly-WORKING live
    // hook before chmod ever ran. Stage-and-swap means a failed chmod touches only the staged
    // copy: the pre-existing file survives byte-identical, nothing 0777 is ever published, and
    // the launch still fails loudly.
    @Test
    fun `a pre-existing executable hook cannot pass for a chmod that failed`(@TempDir tmp: Path) {
        val existing = tmp.resolve("splice-key-capture-hook.sh")
        val originalContent = "#!/bin/sh\npre-existing-working-hook\n"
        Files.writeString(existing, originalContent)
        Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rwxrwxrwx"))

        assertThrows<IOException> {
            LoginInterception.wire(
                configDir = tmp,
                loginCommand = "",
                signInLabel = "OpenRouter",
                globalCommands = null,
                viaBrowser = false,
                tokenCapture = capture,
                log = LogSink { },
                chmod = { _, _ -> throw IOException("injected chmod failure") },
                execProbe = passProbe,
            )
        }
        assertEquals(
            originalContent,
            Files.readString(existing),
            "a failed chmod must leave the pre-existing hook byte-identical, not truncated or deleted",
        )
        assertTrue(
            strayFiles(tmp, "splice-key-capture-hook.sh.*.tmp").isEmpty(),
            "the staged copy must be cleaned up on failure",
        )
    }

    // Second DR-8 redo: the failure funnel caught only what runCatchingCancellable catches
    // (IO/serialization/IAE). setPosixFilePermissions also throws UnsupportedOperationException
    // (non-POSIX filesystem) and SecurityException — either used to fly PAST the funnel entirely.
    // Both must take the same staged-cleanup path.
    @Test
    fun `chmod exceptions outside the IO net still fail the launch and clean the staged copy`(@TempDir tmp: Path) {
        for (thrown in listOf(UnsupportedOperationException("posix not supported"), SecurityException("denied"))) {
            val existing = tmp.resolve("splice-key-capture-hook.sh")
            val originalContent = "#!/bin/sh\npre-existing-working-hook\n"
            Files.writeString(existing, originalContent)
            Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rwxrwxrwx"))

            assertThrows<IOException>("${thrown::class.simpleName} must still fail the launch") {
                LoginInterception.wire(
                    configDir = tmp,
                    loginCommand = "",
                    signInLabel = "OpenRouter",
                    globalCommands = null,
                    viaBrowser = false,
                    tokenCapture = capture,
                    log = LogSink { },
                    chmod = { _, _ -> throw thrown },
                    execProbe = passProbe,
                )
            }
            assertEquals(
                originalContent,
                Files.readString(existing),
                "${thrown::class.simpleName} must leave the pre-existing hook untouched",
            )
            assertTrue(
                strayFiles(tmp, "splice-key-capture-hook.sh.*.tmp").isEmpty(),
                "${thrown::class.simpleName} must not leave the staged copy behind",
            )
        }
    }

    // The swap itself: a successful wire REPLACES a stale pre-existing hook atomically and the
    // published file carries the proven owner-only mode (rename keeps the staged inode).
    @Test
    fun `a successful wire atomically replaces a stale hook with owner-only permissions`(@TempDir tmp: Path) {
        val target = tmp.resolve("splice-key-capture-hook.sh")
        Files.writeString(target, "#!/bin/sh\nstale-old-content\n")
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxrwxrwx"))

        val hooks = wire(tmp, loginCommand = "", tokenCapture = capture, log = mutableListOf())

        assertFalse(hooks.isEmpty(), "the capture hook must register")
        assertFalse(Files.readString(target).contains("stale-old-content"), "the stale body must be replaced")
        assertEquals(
            "rwx------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(target)),
            "the published hook must carry the staged copy's proven mode",
        )
        assertTrue(strayFiles(tmp, "splice-key-capture-hook.sh.*.tmp").isEmpty())
    }

    // DR-31 (codex): a MOVE failure — here the publish target pre-exists as a NON-EMPTY directory
    // that REPLACE_EXISTING cannot atomically swap — must clean the stage and fail the capture
    // launch, the same fail-closed contract as a chmod failure. Exercised past the exec probe so it
    // lands in writeHookScript's move, not the probe short-circuit.
    @Test
    fun `a capture hook whose atomic move fails cleans the stage and fails the launch`(@TempDir tmp: Path) {
        val blocker = tmp.resolve("splice-key-capture-hook.sh")
        Files.createDirectory(blocker)
        Files.writeString(blocker.resolve("occupant"), "x") // non-empty: the atomic move cannot replace it

        assertThrows<IOException> {
            wire(tmp, loginCommand = "", tokenCapture = capture, log = mutableListOf(), execProbe = passProbe)
        }
        assertTrue(
            strayFiles(tmp, "splice-key-capture-hook.sh.*.tmp").isEmpty(),
            "a move failure must not strand the staged copy",
        )
    }

    // DR-31 (codex): an interrupt/cancellation mid-wire — modeled through the chmod seam throwing
    // CancellationException, which Cancellables.runCatchingCancellable deliberately does NOT catch —
    // must still run the finally cleanup AND propagate the cancellation, never swallow it into an
    // IOException nor strand the stage.
    @Test
    fun `a cancellation during staging propagates and still cleans the stage`(@TempDir tmp: Path) {
        assertThrows<CancellationException> {
            LoginInterception.wire(
                configDir = tmp,
                loginCommand = "",
                signInLabel = "OpenRouter",
                globalCommands = null,
                viaBrowser = false,
                tokenCapture = capture,
                log = LogSink { },
                chmod = { _, _ -> throw CancellationException("cancelled mid-wire") },
                execProbe = passProbe,
            )
        }
        assertTrue(
            strayFiles(tmp, "splice-key-capture-hook.sh.*.tmp").isEmpty(),
            "cancellation must not strand the staged copy",
        )
    }

    // DR-31 (codex): two launches writing the capture hook into ONE config dir must not race through
    // a shared stage. With the old fixed "$name.tmp", one writer's move consumed the other's stage
    // (the loser's move hit NoSuchFile) or published a half-written body. A unique per-writer stage
    // makes both moves independent: neither writer fails and the published hook is always one
    // writer's COMPLETE body. A barrier over many rounds makes the old shared-name race reproduce.
    @Test
    fun `concurrent writers of one capture hook never race through a shared stage`(@TempDir tmp: Path) {
        val hookPath = tmp.resolve("splice-key-capture-hook.sh")
        val expected = LoginHookScripts.captureHookScript(capture)
        val failures = ConcurrentLinkedQueue<Throwable>()
        repeat(64) { round ->
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            repeat(2) {
                Thread {
                    start.await()
                    try {
                        wire(
                            tmp,
                            loginCommand = "",
                            tokenCapture = capture,
                            log = mutableListOf(),
                            execProbe = passProbe,
                        )
                    } catch (e: Throwable) {
                        failures += e
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()
            done.await()
            assertEquals(expected, Files.readString(hookPath), "round $round published a torn body")
            assertTrue(
                strayFiles(tmp, "splice-key-capture-hook.sh.*.tmp").isEmpty(),
                "round $round stranded a stage",
            )
        }
        assertTrue(failures.isEmpty(), "no writer may fail on a unique stage: $failures")
    }

    // DR-8 SECURITY (codex): the OLD fixed "$name.tmp" was a predictable path a local peer could
    // pre-plant as a symlink to a victim file — Files.writeString FOLLOWS the symlink and clobbers
    // the victim. A unique createTempFile stage never lands on the pre-planted name, so the victim
    // survives. RED on the fixed-name stage (victim overwritten with the hook body).
    @Test
    fun `a pre-planted symlink at the stage path cannot redirect the write to a victim`(@TempDir tmp: Path) {
        val victim = tmp.resolve("victim")
        val precious = "PRECIOUS — must not be overwritten\n"
        Files.writeString(victim, precious)
        Files.createSymbolicLink(tmp.resolve("splice-key-capture-hook.sh.tmp"), victim)

        wire(tmp, loginCommand = "", tokenCapture = capture, log = mutableListOf(), execProbe = passProbe)

        assertEquals(precious, Files.readString(victim), "the write must not have followed the pre-planted symlink")
    }

    // DR-8 SECURITY (codex): the SAME symlink-follow hazard on the exec probe's own fixed
    // ".splice-exec-probe.tmp". The real probe (createTempFile) must leave a pre-planted victim
    // untouched. RED on a fixed-name probe (the probe's write clobbers the victim).
    @Test
    fun `a pre-planted symlink at the probe path cannot redirect the probe write to a victim`(@TempDir tmp: Path) {
        val victim = tmp.resolve("victim")
        val precious = "PRECIOUS — must not be overwritten\n"
        Files.writeString(victim, precious)
        Files.createSymbolicLink(tmp.resolve(".splice-exec-probe.tmp"), victim)

        wire(tmp, loginCommand = "openrouter login", tokenCapture = null, log = mutableListOf())

        assertEquals(
            precious,
            Files.readString(victim),
            "the probe write must not have followed the pre-planted symlink",
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
