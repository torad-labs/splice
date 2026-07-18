// NEW: materializes the /login interception for a head. Claude Code's built-in Anthropic /login is
// disabled in the head's launch env (DISABLE_LOGIN_COMMAND), and it's a local-jsx command no hook can
// reach anyway; so we (1) drop a custom commands/login.md — which is why the head's `commands` must be
// a REAL dir, not a whole-dir symlink to global — that submits a unique sentinel, and (2) install a
// UserPromptSubmit hook that catches the sentinel, runs the head's codex (ChatGPT) sign-in detached,
// and blocks the model turn. `wire()` returns the settings.json hook entry to merge, or null.
package splice.core.launch

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.runCatchingCancellable
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink

internal object LoginInterception {
    private const val LOGIN_MD = "login.md"
    private const val LOGIN_HOOK_SH = "splice-login-hook.sh"
    private const val LOGIN_SENTINEL = "SPLICE_CODEX_LOGIN"
    private const val USER_PROMPT_SUBMIT = "UserPromptSubmit"
    private const val HOOK_TIMEOUT_SECONDS = 15

    // The custom /login command: submits the sentinel the hook catches. [signInLabel] names the
    // head's provider (e.g. "Codex (ChatGPT)", "Grok (xAI)") so the UX reads right per head.
    private fun loginCommandMd(signInLabel: String): String =
        """
        |---
        |description: Sign in to $signInLabel for this splice head
        |---
        |$LOGIN_SENTINEL
        """.trimMargin() + "\n"

    /**
     * Materialize the /login command + hook. [signInLabel] names the provider for the UX text.
     * [globalCommands] is the operator's ~/.claude/commands to re-link into the head's real commands
     * dir when the policy shares them, or null to skip. Best-effort: I/O failure skips it (null).
     */
    fun wire(configDir: Path, loginCommand: String, signInLabel: String, globalCommands: Path?): JsonObject? {
        if (loginCommand.isBlank()) return null
        return runCatchingCancellable {
            writeCommandsDir(configDir, signInLabel, globalCommands)
            hookEntry(writeHookScript(configDir, loginCommand, signInLabel))
        }.getOrNull()
    }

    /** Merge [entry] into the operator's global UserPromptSubmit hooks (preserving any existing). */
    fun mergeInto(globalHooks: JsonElement?, entry: JsonObject?): JsonObject? {
        val base = globalHooks as? JsonObject
        if (entry == null) return base
        return buildJsonObject {
            base?.forEach { (k, v) -> if (k != USER_PROMPT_SUBMIT) put(k, v) }
            putJsonArray(USER_PROMPT_SUBMIT) {
                (base?.get(USER_PROMPT_SUBMIT) as? JsonArray)?.forEach { add(it) }
                add(entry)
            }
        }
    }

    private fun writeCommandsDir(configDir: Path, signInLabel: String, globalCommands: Path?) {
        val dst = configDir.resolve("commands")
        if (dst.isSymbolicLink()) Files.delete(dst)
        Files.createDirectories(dst)
        if (globalCommands != null) linkGlobalCommandsInto(dst, globalCommands)
        Files.writeString(dst.resolve(LOGIN_MD), loginCommandMd(signInLabel))
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

    private fun writeHookScript(configDir: Path, loginCommand: String, signInLabel: String): Path {
        val script = configDir.resolve(LOGIN_HOOK_SH)
        Files.writeString(script, hookScript(loginCommand, signInLabel))
        runCatchingCancellable { Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------")) }
        return script
    }

    private fun hookEntry(script: Path): JsonObject = buildJsonObject {
        putJsonArray("hooks") {
            addJsonObject {
                put("type", "command")
                put("command", script.toString())
                put("timeout", HOOK_TIMEOUT_SECONDS)
            }
        }
    }

    // No python dependency — globs the raw hook JSON for the unique sentinel (or a raw /login prompt).
    private fun hookScript(loginCommand: String, signInLabel: String): String = buildString {
        val d = "$" // keep the shell $ out of Kotlin interpolation
        appendLine("#!/usr/bin/env bash")
        appendLine("# NEW (splice): /login interception — route to this head's $signInLabel sign-in,")
        appendLine("# not Claude Code's disabled Anthropic login. Runs detached; blocks the model turn.")
        appendLine("input=\"$d(cat)\"")
        appendLine("case \"${d}input\" in")
        appendLine("  *$LOGIN_SENTINEL*|*'\"prompt\":\"/login\"'*|*'\"prompt\": \"/login\"'*)")
        appendLine("    nohup $loginCommand >/dev/null 2>&1 &")
        append("    printf '%s' '{\"decision\":\"block\",\"reason\":")
        append("\"Opening your browser to sign in to $signInLabel — finish there, then continue. ")
        appendLine("If it did not open, run: $loginCommand\"}'")
        appendLine("    ;;")
        appendLine("esac")
        appendLine("exit 0")
    }
}
