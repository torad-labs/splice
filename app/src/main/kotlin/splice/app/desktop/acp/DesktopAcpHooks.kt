// NEW: bridges `splice desktop acp`'s in-process hooks to Splice Console Accounts (`/login`) and the local
// daemon's door hook routes (`POST /hooks/resume/door` for Teams cwd rule-binding and `POST /hooks/foreground/door`
// for foreground tool tracking). Best-effort: never blocks or fails an ACP session when the daemon is down, and
// never reads or writes `~/.gemini`.
package splice.app.desktop.acp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.cli.AdminSupport
import splice.app.cli.status.ConsoleCommand
import splice.client.resume.STARTUP_SOURCE
import splice.core.client.DOOR_HOOK_KEY
import splice.core.client.FOREGROUND_OWNER_ENV
import splice.core.client.FOREGROUND_OWNER_HEADER
import splice.core.client.FOREGROUND_OWNER_LENGTH
import splice.core.client.ForegroundToolActivity
import splice.core.client.ForegroundToolCall
import splice.core.client.ForegroundToolPhase
import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.JsonWire
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.MgmtKeyRead
import splice.provider.antigravity.AcpLoginOpener
import splice.provider.antigravity.AcpSessionHooks
import splice.provider.antigravity.AcpSessionStart
import java.util.UUID

/** Builds [AcpSessionHooks] for a live `splice desktop acp` process under [env]. */
internal class DesktopAcpHooks(private val env: EnvReader) {
    fun build(): AcpSessionHooks {
        val owner = env(FOREGROUND_OWNER_ENV)
            ?.takeIf { it.length == FOREGROUND_OWNER_LENGTH }
            ?: UUID.randomUUID().toString()
        return AcpSessionHooks(
            login = AcpLoginOpener { openConsoleAccounts() },
            sessionStart = AcpSessionStart { id, cwd -> notifyStart(id, cwd, owner) },
            foreground = ForegroundToolActivity { call -> notifyForeground(call) },
            foregroundOwner = owner,
        )
    }

    private fun openConsoleAccounts(): String {
        val lines = mutableListOf<String>()
        val ok = Cancellables.runCatchingBestEffort {
            ConsoleCommand(out = TerminalOutput { lines += it }).open()
        }.getOrDefault(false)
        val joined = lines.joinToString(" ").trim()
        return if (ok && ACP_CONSOLE_OPENED in joined) {
            "Opened Splice Console's Accounts page in your browser, signed in. " +
                "Sign in or manage accounts there; nothing was sent to the model."
        } else {
            val detail = joined.ifBlank { "could not open browser" }
            "Splice Console's Accounts page did not open ($detail). " +
                "Run `splice console` in a terminal to open it; nothing was sent to the model."
        }
    }

    private fun notifyStart(sessionId: String, cwd: String, owner: String) {
        val body = JsonWire.string(
            buildJsonObject {
                put("session_id", sessionId)
                put("cwd", cwd)
                put("source", STARTUP_SOURCE)
            },
        )
        postToDoor("/hooks/resume/$DOOR_HOOK_KEY", body, owner)
    }

    private fun notifyForeground(call: ForegroundToolCall) {
        val phase = when (call.phase) {
            ForegroundToolPhase.START -> "start"
            ForegroundToolPhase.END -> "end"
            ForegroundToolPhase.SESSION_END -> "session_end"
            ForegroundToolPhase.SESSION_START -> return
        }
        val body = JsonWire.string(
            buildJsonObject {
                put("session_id", call.sessionId)
                if (call.toolUseId == null) put("tool_use_id", JsonNull) else put("tool_use_id", call.toolUseId)
                put("phase", phase)
            },
        )
        postToDoor("/hooks/foreground/$DOOR_HOOK_KEY", body, call.owner)
    }

    private fun postToDoor(path: String, body: String, owner: String) {
        Cancellables.runCatchingBestEffort {
            val key = (AdminSupport.readMgmtKey() as? MgmtKeyRead.Present)?.key ?: return@runCatchingBestEffort
            val port = AdminSupport.controlPort(env)
            ControlPlaneClient.postWithHeaders(
                "http://127.0.0.1:$port$path",
                key,
                body,
                ACP_HOOK_TIMEOUT_MS,
                mapOf(FOREGROUND_OWNER_HEADER to owner),
            )
        }
    }
}

private const val ACP_CONSOLE_OPENED = "opened the console"

// why: best-effort loopback hook POST bounded at 1s so a busy daemon never stalls an ACP turn.
private const val ACP_HOOK_TIMEOUT_MS = 1_000
