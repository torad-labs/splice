// NEW: materializes the /login interception + api-key token capture for a head. Claude Code's
// built-in Anthropic /login is disabled in the head's launch env (DISABLE_LOGIN_COMMAND), and
// it's a local-jsx command no hook can reach anyway; so we (1) drop a custom commands/login.md —
// which is why the head's `commands` must be a REAL dir, not a whole-dir symlink to global — that
// submits a unique sentinel, and (2) install a UserPromptSubmit hook that catches the sentinel,
// runs the head's sign-in detached, and blocks the model turn. For api-key heads we additionally
// (3) install a token-capture hook: a BARE provider token pasted as the whole message is stored
// to keys.toml via `splice key set --stdin` and BLOCKED before it ever reaches the model context
// (so it never travels upstream through the gateway), and (4) — only while the key is missing —
// a SessionStart advertiser that tells the model to offer the paste flow. The bash texts live in
// LoginHookScripts.kt; wire() returns the settings.json hook entries to merge, keyed by event.
package splice.core.launch

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.config.envNameRegex
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink

/** What an api-key head captures from the prompt box. [tokenPattern] is a bash ERE matched as
 *  the WHOLE prompt (quote-anchored in the hook JSON) — a token embedded in prose never matches,
 *  so discussing a key never triggers capture. */
public data class TokenCaptureSpec(
    val envVar: String, // e.g. OPENROUTER_API_KEY
    val tokenPattern: String, // e.g. sk-or-[A-Za-z0-9_-]{20,}
    val providerLabel: String, // e.g. "OpenRouter"
) {
    init {
        // [envVar] is operator-authored (`auth = { kind = "api-key", env = "..." }`, shipped as a
        // documented knob in splice.example.toml) and lands as a BARE UNQUOTED command word in the
        // generated capture hook: `splice key set $envVar --stdin`. `env = "A;B"` emits two commands;
        // an unbalanced quote or paren makes the enclosing `if` a syntax error and the hook — which
        // bash parses on every prompt for that head, and which nobody ever opens — stays broken from
        // then on. KeyStore already OWNS this check and enforces it at KeyStore.write, but that runs
        // when the GENERATED script invokes `splice key set`, after bash has already parsed the
        // interpolated text. Same regex, one step earlier (review 2026-08-28, PR 99).
        require(envVar.matches(envNameRegex)) { "api-key env name must match $envNameRegex: '$envVar'" }
    }
}

/** Applies the owner-only executable mode to a generated hook script. A seam because the one step a
 *  test must be able to fail on demand is exactly this one — no temp filesystem refuses a chmod —
 *  and the capture hook's entire security value is that the mode took. */
internal fun interface HookChmod {
    operator fun invoke(script: Path, perms: Set<PosixFilePermission>)
}

/** Proves [dir] can EXECUTE a fresh owner-only script, or names why not (DR-8 redo-2, 2026-08-31).
 *  Chmod success is not executability: on a noexec mount every mode bit sets while exec returns
 *  EACCES, so a registered hook can never run — and for the capture hook that means a pasted
 *  credential reaches the model. Only an actual exec settles it; injected so the counterexample
 *  is a deterministic test rather than a root-only noexec mount. */
internal fun interface HookExecProbe {
    operator fun invoke(dir: Path, chmod: HookChmod): Throwable?
}

internal object LoginInterception {
    private const val COMMANDS_DIR = "commands"
    private const val LOGIN_MD = "login.md"
    private const val LOGIN_HOOK_SH = "splice-login-hook.sh"
    private const val CAPTURE_HOOK_SH = "splice-key-capture-hook.sh"
    private const val KEYSETUP_HOOK_SH = "splice-keysetup-hook.sh"
    private const val LOGIN_SENTINEL = "SPLICE_CODEX_LOGIN"
    private const val USER_PROMPT_SUBMIT = "UserPromptSubmit"
    private const val SESSION_START = "SessionStart"
    private const val HOOK_TIMEOUT_SECONDS = 15

    /**
     * Materialize the /login command + hooks. [signInLabel] names the provider for the UX text;
     * [viaBrowser] false switches the block reason to the masked-terminal-prompt wording (api-key
     * heads). [tokenCapture] adds the bare-token capture hook. [globalCommands] is the operator's
     * ~/.claude/commands to re-link into the head's real commands dir when the policy shares them,
     * or null to skip.
     *
     * Failure contract (DR-8): the /login-interceptor leg is best-effort — an I/O failure skips
     * just that leg and LOGS the cause (one wrap around everything used to collapse three
     * different obstructions into one unlogged empty map). The token-capture leg is fail-closed —
     * it THROWS out of materialize(), because a head that cannot install the hook that stops a
     * pasted credential from reaching the model must fail its launch, not come up uninterceptable.
     */
    fun wire(
        configDir: Path,
        loginCommand: String,
        signInLabel: String,
        globalCommands: Path?,
        viaBrowser: Boolean = true,
        tokenCapture: TokenCaptureSpec? = null,
        loginOutcomeFile: String = "",
        log: LogSink = LogSink(DaemonLog::write),
        chmod: HookChmod = HookChmod(Files::setPosixFilePermissions),
        execProbe: HookExecProbe = HookExecProbe(LoginInterception::probeExecutability),
    ): Map<String, List<JsonObject>> {
        if (loginCommand.isBlank()) reconcileBlankLoginCommands(configDir, globalCommands, log)
        if (loginCommand.isBlank() && tokenCapture == null) return emptyMap()
        if (!dirCanExecuteHooks(configDir, tokenCapture, log, chmod, execProbe)) return emptyMap()
        val upsHooks = mutableListOf<JsonObject>()
        if (loginCommand.isNotBlank()) {
            val leg = Cancellables.runCatchingCancellable {
                writeCommandsDir(configDir, signInLabel, globalCommands)
                val script = writeHookScript(
                    configDir,
                    LOGIN_HOOK_SH,
                    LoginHookScripts.loginHookScript(
                        LoginHookSpec(
                            loginCommand = loginCommand,
                            signInLabel = signInLabel,
                            viaBrowser = viaBrowser,
                            sentinel = LOGIN_SENTINEL,
                            outcomeFile = loginOutcomeFile,
                            canCapturePaste = tokenCapture != null,
                        ),
                    ),
                    chmod,
                )
                upsHooks += hookEntry(script, HOOK_TIMEOUT_SECONDS)
            }
            if (leg.isFailure) {
                log(
                    "[login] /login interception NOT installed in $configDir " +
                        "(${leg.exceptionOrNull()?.message}) — commands/login.md or its hook failed; " +
                        "the head runs without an interceptor\n",
                )
            }
        }
        if (tokenCapture != null) {
            val script =
                writeHookScript(configDir, CAPTURE_HOOK_SH, LoginHookScripts.captureHookScript(tokenCapture), chmod)
            upsHooks += hookEntry(script, HOOK_TIMEOUT_SECONDS)
        }
        return if (upsHooks.isEmpty()) emptyMap() else mapOf(USER_PROMPT_SUBMIT to upsHooks)
    }

    /** The SessionStart advertiser — installed by the materializer ONLY while the key is missing
     *  (the daemon checks at every launch), so it can print unconditionally. */
    fun keySetupAdvertiser(
        configDir: Path,
        spec: TokenCaptureSpec,
        loginCommand: String,
        log: LogSink = LogSink(DaemonLog::write),
        chmod: HookChmod = HookChmod(Files::setPosixFilePermissions),
        execProbe: HookExecProbe = HookExecProbe(LoginInterception::probeExecutability),
    ): Map<String, List<JsonObject>> {
        val leg = Cancellables.runCatchingCancellable {
            execProbe(configDir, chmod)?.let { failure ->
                throw IOException("$configDir cannot execute a staged hook (${failure.message})")
            }
            val script =
                writeHookScript(configDir, KEYSETUP_HOOK_SH, LoginHookScripts.keySetupScript(spec, loginCommand), chmod)
            mapOf(SESSION_START to listOf(hookEntry(script, HOOK_TIMEOUT_SECONDS)))
        }
        if (leg.isFailure) {
            log(
                "[login] key-setup advertiser NOT installed in $configDir " +
                    "(${leg.exceptionOrNull()?.message}) — the paste flow stays undiscoverable this launch\n",
            )
        }
        return leg.getOrElse { emptyMap() }
    }

    /** Concatenate two hook-addition maps per event — a plain map `+` would silently overwrite
     *  a duplicate event key (e.g. capture hook + login hook both landing on UserPromptSubmit). */
    fun concat(
        a: Map<String, List<JsonObject>>,
        b: Map<String, List<JsonObject>>,
    ): Map<String, List<JsonObject>> =
        (a.keys + b.keys).associateWith { k -> a[k].orEmpty() + b[k].orEmpty() }

    /** Merge [additions] (event -> entries) into the operator's global hooks, preserving any
     *  existing entries per event. */
    fun mergeInto(globalHooks: JsonElement?, additions: Map<String, List<JsonObject>>): JsonObject? {
        val base = globalHooks as? JsonObject
        if (additions.isEmpty()) return base
        val events = (base?.keys.orEmpty() + additions.keys).toSet()
        if (events.isEmpty()) return null
        return buildJsonObject {
            for (event in events) {
                val existing = (base?.get(event) as? JsonArray).orEmpty()
                val added = additions[event].orEmpty()
                if (existing.isEmpty() && added.isEmpty()) continue
                putJsonArray(event) {
                    existing.forEach { add(it) }
                    added.forEach { add(it) }
                }
            }
        }
    }

    /** DR-39 redo (codex): commands reconciliation is not login plumbing. A client-auth head
     *  (blank loginCommand) still shares the operator's commands, and the materializer EXEMPTS
     *  commands' real-dir decline on the promise that THIS file reconciles them — so for a
     *  blank-login head the promise must hold before wire()'s login-specific early return. Only
     *  the head's-own-REAL-dir shape needs work: a whole-dir symlink already IS the share, and an
     *  absent dir is linked whole by the materializer. No login.md is written — a /login command
     *  on a client-auth head would be wrong. */
    private fun reconcileBlankLoginCommands(configDir: Path, globalCommands: Path?, log: LogSink) {
        if (globalCommands == null) return
        val dst = configDir.resolve(COMMANDS_DIR)
        if (!Files.isDirectory(dst, NOFOLLOW_LINKS) || dst.isSymbolicLink()) return
        val leg = Cancellables.runCatchingCancellable { linkGlobalCommandsInto(dst, globalCommands) }
        if (leg.isFailure) {
            log(
                "[login] shared commands NOT reconciled into $configDir " +
                    "(${leg.exceptionOrNull()?.message}) — this head's own commands dir is " +
                    "missing the operator's entries\n",
            )
        }
    }

    private fun writeCommandsDir(configDir: Path, signInLabel: String, globalCommands: Path?) {
        val dst = configDir.resolve(COMMANDS_DIR)
        val symlinked = dst.isSymbolicLink()
        // A whole-dir commands symlink must become the real dir login.md lives in — but the old
        // delete-then-createDirectories-then-populate lost the WORKING commands dir whenever a step
        // after the delete failed (DR-39: ENOSPC/EPERM mid-populate). The real dir is now staged
        // COMPLETE beside the link first; only unlink+rename remain after it is whole. A stale
        // stage from a crashed attempt is a createDirectories no-op (dir) or a loud pre-delete
        // failure (file) that leaves the link untouched.
        val target = if (symlinked) {
            Files.createDirectories(configDir.resolve(".$COMMANDS_DIR.staged-${ProcessHandle.current().pid()}"))
        } else {
            Files.createDirectories(dst)
        }
        if (globalCommands != null) linkGlobalCommandsInto(target, globalCommands)
        Files.writeString(target.resolve(LOGIN_MD), LoginHookScripts.loginCommandMd(signInLabel, LOGIN_SENTINEL))
        if (symlinked) {
            Files.delete(dst)
            Files.move(target, dst)
        }
    }

    private fun linkGlobalCommandsInto(dst: Path, globalCommands: Path) {
        if (!globalCommands.isDirectory()) return
        Files.newDirectoryStream(globalCommands).use { entries ->
            entries.filter { it.fileName.toString() != LOGIN_MD }.forEach { linkOneInto(dst, it) }
        }
    }

    private fun linkOneInto(dir: Path, src: Path) {
        val dst = dir.resolve(src.fileName.toString())
        val present = Files.exists(dst, NOFOLLOW_LINKS)
        // Steady state first (DR-39): commands reconcile on EVERY launch, and most launches find
        // the link already correct — the old unconditional delete-and-recreate churned the inode
        // and opened a window where a crash between the two syscalls left the command MISSING. A
        // correct link is a no-op; a real directory is operator content and stays; only a stale
        // file or wrong-target link is replaced, via a staged sibling published in ONE atomic
        // rename so no reader ever sees the name absent. A staged-name collision (crashed attempt
        // debris, or a fault-injection double) fails BEFORE dst is touched.
        val alreadyCorrect = present && dst.isSymbolicLink() &&
            Cancellables.runCatchingCancellable { Files.readSymbolicLink(dst) }.getOrNull() == src
        when {
            alreadyCorrect || (present && dst.isDirectory(NOFOLLOW_LINKS)) -> Unit
            !present -> Files.createSymbolicLink(dst, src)
            else -> {
                val staged = dir.resolve(".${dst.fileName}.staged-${ProcessHandle.current().pid()}")
                Files.createSymbolicLink(staged, src)
                Files.move(staged, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /**
     * The generated hook script, owner-only and EXECUTABLE — or an [IOException] (DR-8 redo).
     *
     * A chmod failure used to be logged and shallowed here, which quietly defeated the fail-closed
     * capture leg above: the script was written 0644, [hookEntry] registered its path in
     * settings.json, and Claude Code could not run it. A registered hook that cannot execute is
     * indistinguishable from no hook at all, so for the capture leg it means a pasted credential
     * reaches the model — the exact outcome that leg exists to prevent. Throwing instead lets each
     * caller's EXISTING wrap decide the policy: the login and advertiser legs catch it and log a
     * dropped leg, the unwrapped capture leg fails the launch.
     *
     * The condition is the OUTCOME, not the call: [Files.isExecutable] holds on a filesystem that
     * ignores modes but mounts exec (the LNC-005 case this used to tolerate wholesale) and fails on
     * one that leaves the script unrunnable, whatever the chmod itself reported.
     */
    /** Stage-and-swap (DR-31): Claude Code parses these scripts on every prompt, so the LIVE hook
     *  must never be observable truncated, torn, or mode-broken. Content and mode land on a staged
     *  same-dir copy FIRST; the atomic move publishes it whole (rename keeps the inode, so the
     *  proven mode travels with it). Any failure deletes only the staged copy — a pre-existing
     *  working hook stays untouched and the launch fails loudly.
     *
     *  The chmod outcome is the ONLY mode probe (DR-8 redo: a seeded rwxrwxrwx file passes
     *  isExecutable while anyone may rewrite what the hook runs), and the catch net is wider than
     *  runCatchingCancellable's IO/serialization/IAE because setPosixFilePermissions also throws
     *  UnsupportedOperationException (non-POSIX fs) and SecurityException (second DR-8 redo). */
    /** DR-8 redo-2 (codex noexec catch): prove the directory can execute an owner-only script
     *  BEFORE anything is staged or registered — a hook that registers but cannot run is
     *  indistinguishable from no hook. Per-leg policy holds: with a capture spec the launch fails
     *  (fail-closed on the credential interceptor); without one the head degrades loudly and
     *  registers nothing, because registering known-unrunnable hooks is the defect. */
    private fun dirCanExecuteHooks(
        configDir: Path,
        tokenCapture: TokenCaptureSpec?,
        log: LogSink,
        chmod: HookChmod,
        execProbe: HookExecProbe,
    ): Boolean {
        val execFailure = execProbe(configDir, chmod) ?: return true
        if (tokenCapture != null) {
            throw IOException(
                "$configDir cannot execute a staged hook (${execFailure.message}) — the capture " +
                    "hook would register but never run; refusing to launch uninterceptable",
            )
        }
        log(
            "[login] hooks NOT installed in $configDir (${execFailure.message}) — the directory " +
                "cannot execute scripts (noexec mount?); the head runs without an interceptor\n",
        )
        return false
    }

    /** The real [HookExecProbe]: write a throwaway owner-only `exit 0` script beside the hooks and
     *  RUN it. Executability is a property of the mount + mode + uid, not of content, so a sibling
     *  probe file proves exactly what the hook needs without executing any hook logic. EACCES from
     *  a noexec mount surfaces here as ProcessBuilder's IOException — the codex /run/lock repro. */
    private fun probeExecutability(dir: Path, chmod: HookChmod): Throwable? {
        // DR-8 redo-3 (codex symlink catch): a FIXED ".splice-exec-probe.tmp" was a predictable
        // path a local peer could pre-plant as a symlink (the write would follow it and clobber the
        // victim) and a shared name two concurrent launches raced. createTempFile picks a random
        // name and creates it with CREATE_NEW (O_EXCL), which refuses ANY pre-existing path —
        // symlink or dangling symlink included — so the write can only land on the fresh regular
        // file it just made. Creation runs INSIDE the try, so a creation failure returns as the
        // probe's Throwable (fail-closed); the finally deletes only a probe that was created.
        var probe: Path? = null
        return try {
            probe = Files.createTempFile(dir, ".splice-exec-probe.", ".tmp")
            Files.writeString(probe, "#!/bin/sh\nexit 0\n")
            chmod(probe, PosixFilePermissions.fromString("rwx------"))
            val process = ProcessBuilder(probe.toString()).redirectErrorStream(true).start()
            if (!process.waitFor(HOOK_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)) {
                process.destroyForcibly()
                IOException("exec probe timed out after ${HOOK_TIMEOUT_SECONDS}s")
            } else if (process.exitValue() != 0) {
                IOException("exec probe exited ${process.exitValue()}")
            } else {
                null
            }
        } catch (e: IOException) {
            e
        } catch (e: UnsupportedOperationException) {
            e
        } catch (e: SecurityException) {
            e
        } finally {
            probe?.let { p -> Cancellables.runCatchingCancellable { Files.deleteIfExists(p) } }
        }
    }

    private fun writeHookScript(configDir: Path, name: String, content: String, chmod: HookChmod): Path {
        val script = configDir.resolve(name)
        // DR-8/DR-31 redo (codex): a FIXED "$name.tmp" stage was a predictable path (symlink
        // pre-plant → the write clobbers a victim) AND a shared name two concurrent launches raced
        // (A's move published B's body). createTempFile picks a unique random name and creates it
        // with CREATE_NEW (O_EXCL) in the SAME dir, so each launch owns its own stage and the write
        // cannot follow a pre-existing symlink. The finally removes the stage on EVERY exit that
        // did not consume it by move — write failure, chmod failure, an interrupt/cancellation
        // mid-write — so no ".tmp" is ever stranded; the live hook is untouched until move succeeds.
        val staged = Files.createTempFile(configDir, "$name.", ".tmp")
        var moved = false
        try {
            Files.writeString(staged, content)
            val chmodFailure = try {
                Cancellables.runCatchingCancellable {
                    chmod(staged, PosixFilePermissions.fromString("rwx------"))
                }.exceptionOrNull()
            } catch (e: UnsupportedOperationException) {
                e
            } catch (e: SecurityException) {
                e
            }
            if (chmodFailure != null) {
                throw IOException(
                    "$script: chmod rwx------ failed on the staged copy (${chmodFailure.message}) — " +
                        "staged file deleted, any existing hook left untouched",
                )
            }
            Files.move(staged, script, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            moved = true
            return script
        } finally {
            if (!moved) Cancellables.runCatchingCancellable { Files.deleteIfExists(staged) }
        }
    }

    private fun hookEntry(script: Path, timeoutSeconds: Int): JsonObject = buildJsonObject {
        putJsonArray("hooks") {
            addJsonObject {
                put("type", "command")
                put("command", script.toString())
                put("timeout", timeoutSeconds)
            }
        }
    }
}
