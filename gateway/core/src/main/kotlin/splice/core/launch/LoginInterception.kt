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
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
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

internal object LoginInterception {
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
    ): Map<String, List<JsonObject>> {
        if (loginCommand.isBlank() && tokenCapture == null) return emptyMap()
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
    ): Map<String, List<JsonObject>> {
        val leg = Cancellables.runCatchingCancellable {
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

    private fun writeCommandsDir(configDir: Path, signInLabel: String, globalCommands: Path?) {
        val dst = configDir.resolve("commands")
        if (dst.isSymbolicLink()) Files.delete(dst)
        Files.createDirectories(dst)
        if (globalCommands != null) linkGlobalCommandsInto(dst, globalCommands)
        Files.writeString(dst.resolve(LOGIN_MD), LoginHookScripts.loginCommandMd(signInLabel, LOGIN_SENTINEL))
    }

    private fun linkGlobalCommandsInto(dst: Path, globalCommands: Path) {
        if (!globalCommands.isDirectory()) return
        Files.newDirectoryStream(globalCommands).use { entries ->
            entries.filter { it.fileName.toString() != LOGIN_MD }.forEach { linkOneInto(dst, it) }
        }
    }

    private fun linkOneInto(dir: Path, src: Path) {
        val dst = dir.resolve(src.fileName.toString())
        if (Files.exists(dst, NOFOLLOW_LINKS)) {
            if (dst.isSymbolicLink() || !dst.isDirectory()) Files.delete(dst) else return
        }
        Files.createSymbolicLink(dst, src)
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
    private fun writeHookScript(configDir: Path, name: String, content: String, chmod: HookChmod): Path {
        val script = configDir.resolve(name)
        Files.writeString(script, content)
        val chmodFailure = Cancellables.runCatchingCancellable {
            chmod(script, PosixFilePermissions.fromString("rwx------"))
        }.exceptionOrNull()
        if (!Files.isExecutable(script)) {
            throw IOException(
                "$script is not executable after chmod rwx------ " +
                    "(${chmodFailure?.message ?: "the chmod reported success"}) — Claude Code cannot run " +
                    "a hook it is told to run",
            )
        }
        return script
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
