// NEW: Nov 12, 2026: the daemon itself tells the operator when a command is on the last login that can serve its
// sessions (LoginNotices), through the outlets they set: [daemon] alert_command in splice.toml and the webhook saved in
// the console.
//
// SAID ONCE: a notice has a key that changes when what it says changes, and the keys already said are kept in a file,
// so a restart does not say them again. A key is forgotten once its notice has not held for half an hour: a login
// drops out for minutes at a time (a short hold, a token being renewed), and a notice that came and went with it is
// not news, while one that returns hours later is. A notice whose command failed is not marked said and is tried
// again at the next check; the failure is logged when it changes, not once a minute.
//
// A COMMAND SET FOR THE FIRST TIME is sent one line saying what it will be used for. That is also how a wrong command
// shows on the day it is set, and not on the day it matters.
package splice.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.app.control.AccountRoster
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import splice.diagnostics.doctor.accounts.LoginNotice
import splice.diagnostics.doctor.accounts.LoginNotices
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker
import splice.usage.alerts.AlertCommand
import splice.usage.alerts.AlertDelivery
import java.nio.file.Files
import java.nio.file.Path

// why: under a full load a login's reading moves about half a point a minute, so a minute between checks loses nothing.
private const val CHECK_MS = 60_000L

// why: the holds that take a login out for a moment last minutes (a provider's short hold is five, a refused
// organization's is fifteen), so half an hour without a notice holding tells a return from a flicker.
private const val FORGET_MS = 1_800_000L

internal const val LOGIN_ALERTS_FILE = "login-alerts.json"

private const val HELLO = "splice will tell you here when a command is on the last login that can serve its " +
    "sessions and that login is near a limit."

/** What has been said: the command greeted, and the keys of the notices said and not forgotten. */
@Serializable
private data class SaidAlerts(val greeted: String? = null, val said: Set<String> = emptySet())

internal class LoginAlerting(
    private val roster: AccountRoster,
    private val command: AlertCommand,
    private val webhook: AlertDelivery,
    private val file: Path,
    private val log: LogSink,
    private val now: WallClock = WallClock { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val notices = LoginNotices(now)
    private val posted = HashSet<String>()
    private var complained: String? = null

    /** When each said key's notice last held, by this process's clock; a key read from the file starts at its first
     *  check, so a restart forgets nothing early. */
    private val held = HashMap<String, Long>()

    /** Checks every [CHECK_MS] in [scope]. A check that fails is logged and the next one runs. */
    fun watch(scope: CoroutineScope, ticker: Ticker = ProcessTicker()) {
        scope.launch {
            while (ticker.awaitTick(CHECK_MS)) {
                Cancellables.runCatchingCancellable { check() }.onFailure {
                    log("[alerts] logins not checked (${SafeFailureText.render(it)})\n")
                }
            }
        }
    }

    /** One check: greets a command not greeted yet, says each notice not said yet, and forgets the said ones that
     *  have not held for [FORGET_MS]. With no outlet set it reads nothing. */
    suspend fun check() {
        if (!command.set && !webhook.set) return
        val onDisk = load()
        val before = onDisk ?: SaidAlerts()
        val greeted = greeted(before.greeted)
        val at = now()
        val holding = notices.of(roster.json()).filter { it.key in before.said || tell(it) }.map { it.key }
        holding.forEach { held[it] = at }
        val remembered = before.said.filter { at - held.getOrPut(it) { at } < FORGET_MS }
        held.keys.retainAll(remembered + holding)
        val after = SaidAlerts(greeted, (remembered + holding).toSet())
        if (after != onDisk) SecureFile.writeAtomic0600(file, json.encodeToString(SaidAlerts.serializer(), after))
    }

    private suspend fun greeted(before: String?): String? {
        val due = command.set && before != command.mark
        return if (due && ran("splice", HELLO)) command.mark else before
    }

    private suspend fun tell(notice: LoginNotice): Boolean {
        if (posted.add(notice.key)) webhook.accounts(notice.head, notice.text)
        val delivered = !command.set || ran(notice.head, notice.text)
        if (delivered) {
            posted -= notice.key
            log("[${LogSafe.str(notice.head)}][alerts] said: ${LogSafe.str(notice.key)}\n")
        }
        return delivered
    }

    /** Runs the command off this coroutine's thread. A failure is logged when it differs from the last one. */
    private suspend fun ran(head: String, text: String): Boolean {
        val failed = withContext(ProcessDispatchers().io()) { command.say(text) }
        if (failed != null && failed != complained) {
            log("[${LogSafe.str(head)}][alerts] the alert command did not deliver: $failed; trying again each minute\n")
        }
        complained = failed
        return failed == null
    }

    /** What the file holds, or null when there is none yet or it cannot be read. An unreadable one is logged and the
     *  check writes it again, so it is logged once. */
    private fun load(): SaidAlerts? = if (!Files.exists(file)) {
        null
    } else {
        Cancellables.runCatchingCancellable { json.decodeFromString(SaidAlerts.serializer(), Files.readString(file)) }
            .onFailure {
                log("[alerts] $LOGIN_ALERTS_FILE not read (${SafeFailureText.render(it)}); starting it again\n")
            }
            .getOrNull()
    }
}
