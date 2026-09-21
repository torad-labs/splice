// NEW: V4-169 (2026-09-19) — the moment the daemon learns WHICH session the `-r` picker or `-c`
// chose. At launch the daemon knows a session id only for `-r SESSION_ID`; the picker and `-c` pick
// inside Claude Code, after launch. Claude Code tells us: its SessionStart hook fires with
// `source: "resume"`, the `session_id` and the `transcript_path`. This installs that hook — matcher
// "resume" only, so a fresh start, /clear and a compaction never call — and the script POSTs the hook
// JSON to the daemon's /hooks/resume/<head>, which moves that transcript onto the head's model
// (TranscriptModelRewrite, the same rewrite the `-r SESSION_ID` launch runs).
//
// NO SECRET IN THE SCRIPT: the session's own environment carries ANTHROPIC_AUTH_TOKEN, which is the
// management bearer the launch recipe planted, so the script authenticates from its env and the file
// holds two literals only — the control port and the head key. The operator's statusline command
// carries the bearer inline in settings.json already; a 0700 script beside it does not need to.
//
// NEVER BLOCKS A SESSION: the script exits 0 whatever curl answers, bounded by its own -m and the
// hook timeout, and the daemon answers 200 to everything it refuses (it logs the refusal instead).
// A hook that could fail a resume would be worse than the one-line notice it exists to remove.
package splice.client.resume

import kotlinx.serialization.json.JsonObject
import splice.client.login.HookChmod
import splice.client.login.HookExecProbe
import splice.client.login.HookScriptFiles
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** The SessionStart `source` value Claude Code sends for --resume, --continue and /resume — the one
 *  declaration, read by the hook installer here and by the control route that receives the call. */
public const val RESUME_SOURCE: String = "resume"

internal object ResumeHook {
    const val RESUME_HOOK_SH: String = "splice-resume-hook.sh"

    // why: the hook is one loopback POST; five seconds is far above a daemon on the same box and far
    // below the hook timeout, so a stalled daemon costs a resume five seconds, never fifteen.
    private const val CURL_TIMEOUT_S = 5

    /** [headKey] is the topology key LaunchSpecFactory passes — `[A-Za-z0-9_-]`, validated at load —
     *  and it lands inside a double-quoted URL, so it is written as is. */
    fun script(controlPort: Int, headKey: String): String = buildString {
        appendLine("#!/usr/bin/env bash")
        appendLine("# NEW (splice, V4-169): on `--resume` / `--continue` / /resume, tell the daemon which session")
        appendLine("# this head is resuming so its assistant rows are moved onto this head's model. Authenticates")
        appendLine("# with the session's own ANTHROPIC_AUTH_TOKEN; never blocks the session (exit 0 always).")
        appendLine("[ -n \"\${ANTHROPIC_AUTH_TOKEN:-}\" ] || exit 0")
        appendLine("curl -sS -m $CURL_TIMEOUT_S -X POST -H \"Authorization: Bearer \${ANTHROPIC_AUTH_TOKEN}\" \\")
        appendLine("  -H 'Content-Type: application/json' --data-binary @- \\")
        appendLine("  \"http://127.0.0.1:$controlPort/hooks/resume/$headKey\" >/dev/null 2>&1 || true")
        appendLine("exit 0")
    }

    /** The hook additions for the materializer: one SessionStart entry, matcher "resume". An install
     *  failure is said and the head launches without the hook — the resume then costs the notice
     *  this hook removes, nothing more. */
    fun install(
        configDir: Path,
        controlPort: Int,
        headKey: String,
        log: LogSink = LogSink(DaemonLog::write),
        chmod: HookChmod = HookChmod(Files::setPosixFilePermissions),
        execProbe: HookExecProbe? = null,
    ): Map<String, List<JsonObject>> {
        val leg = Cancellables.runCatchingCancellable {
            execProbe?.invoke(configDir, chmod)?.let { failure ->
                // SAFE-RENDER-EXEMPT[2026-09-19]: an exec-bit probe on a directory we create — the failure names that directory, never file content
                throw IOException("$configDir cannot execute a staged hook (${failure.message})")
            }
            val script = HookScriptFiles.writeHookScript(configDir, RESUME_HOOK_SH, script(controlPort, headKey), chmod)
            mapOf(
                HookScriptFiles.SESSION_START to listOf(
                    HookScriptFiles.hookEntry(script, HookScriptFiles.HOOK_TIMEOUT_SECONDS, matcher = RESUME_SOURCE),
                ),
            )
        }
        if (leg.isFailure) {
            log(
                "[resume] resume hook NOT installed in $configDir " +
                    // SAFE-RENDER-EXEMPT[2026-09-19]: a staged hook copy — a FileSystemException over paths this code authored, never content
                    "(${leg.exceptionOrNull()?.message}) — a session resumed on this head keeps the model " +
                    "id of the head that wrote it, so Claude Code prints its restore notice once\n",
            )
        }
        return leg.getOrElse { emptyMap() }
    }
}
