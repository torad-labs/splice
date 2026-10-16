// NEW: Oct 16, 2026 (Marcos: "Everything that the user can do the agents should be able to do it too"). The relay
// that lets `splice console <action>` drive the console a person has open. The CLI posts one order; the daemon hands
// it to the tab the person looked at last, which acts on its own page (pilot.js) and posts its answer back, and the
// CLI prints that answer. The daemon reads nothing in an order but its action word: what a tap or a fill does is the
// page's to decide, so a new console control needs no daemon change to be driven.
package splice.app.control

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.http.JsonReply
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** How long a tab's ask for its next order is held open before it answers "nothing" and the tab asks again. */
private const val ASK_MS = 20_000L

/** A tab that has not asked in this long is gone: closed, hidden, or its machine asleep. */
private const val GONE_MS = ASK_MS + 10_000L

/** How long an order waits for the page's answer. A move to another page loads that page's script first. */
private const val ANSWER_MS = 15_000L
private const val MS_PER_SECOND = 1000L

/** A tab names itself with a random word the page draws once; anything longer is not one of ours. */
private const val MAX_TAB_NAME = 64

/** An order carries a few words and a target, never a document. */
private const val MAX_ORDER_CHARS = 65_536

internal class ConsolePilot {

    /** One open console tab: the orders waiting for its next ask, and the ask the daemon is holding open for it. */
    private class Tab {
        val queue = ArrayDeque<String>()
        var waiter: CompletableDeferred<String?>? = null

        @Volatile
        var askedAt = 0L

        @Volatile
        var focusedAt = 0L
    }

    private val tabs = ConcurrentHashMap<String, Tab>()
    private val answers = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val json = Json { ignoreUnknownKeys = true }

    /** The CLI's side: one order for the tab the person looked at last, answered with what the page said. */
    suspend fun order(body: String): JsonReply {
        val order = JsonScalars.objectOrNull(json, body)?.takeIf { body.length <= MAX_ORDER_CHARS }
        if (order == null || JsonScalars.str(order, "action").isNullOrBlank()) {
            return refused(HttpStatusCode.BadRequest, "an order is a JSON object that names an action")
        }
        val (name, tab) = pick() ?: return refused(
            HttpStatusCode.Conflict,
            "no console is open in a browser. Open it with `splice console`, then try again.",
        )
        val id = UUID.randomUUID().toString()
        val answer = CompletableDeferred<String>()
        answers[id] = answer
        try {
            deliver(tab, JsonObject(order + ("id" to JsonPrimitive(id))).toString())
            val said = withTimeoutOrNull(ANSWER_MS) { answer.await() }
            return said?.let { JsonReply(HttpStatusCode.OK, it) } ?: unanswered(name, tab)
        } finally {
            answers.remove(id)
        }
    }

    /** A tab that was handed an order and never answered is gone, so the next order goes to another one. */
    private fun unanswered(name: String, tab: Tab): JsonReply {
        tabs.remove(name, tab)
        return refused(
            HttpStatusCode.GatewayTimeout,
            "the console did not answer in ${ANSWER_MS / MS_PER_SECOND} s. It may have closed: " +
                "open it with `splice console` and try again.",
        )
    }

    /** The page's side: its ask for the next order, held open until one comes or [ASK_MS] passes (204). */
    suspend fun next(tabName: String?, focused: Boolean): JsonReply {
        if (tabName == null || !ownName(tabName)) {
            return refused(HttpStatusCode.BadRequest, "a tab asks under its own name")
        }
        val now = System.currentTimeMillis()
        val tab = tabs.computeIfAbsent(tabName) { Tab() }
        tab.askedAt = now
        if (focused) tab.focusedAt = now
        val wait = synchronized(tab) {
            tab.queue.removeFirstOrNull()?.let { return JsonReply(HttpStatusCode.OK, it) }
            // A newer ask replaces an older one: the page that sent the older one has stopped listening to it.
            tab.waiter?.complete(null)
            CompletableDeferred<String?>().also { tab.waiter = it }
        }
        val got = withTimeoutOrNull(ASK_MS) { wait.await() } ?: lateOrder(tab, wait)
        return got?.let { JsonReply(HttpStatusCode.OK, it) } ?: JsonReply(HttpStatusCode.NoContent, "")
    }

    /** The page's answer to the order it was handed, passed to the CLI that is waiting on it. */
    fun answer(body: String): JsonReply {
        val id = JsonScalars.str(JsonScalars.objectOrNull(json, body), "id")
        val waiting = id?.let(answers::remove)
            ?: return refused(HttpStatusCode.NotFound, "no order is waiting on that answer")
        waiting.complete(body)
        return JsonReply(HttpStatusCode.OK, "{}")
    }

    /** A tab that is closing or hidden takes no more orders. */
    fun leave(tabName: String?): JsonReply {
        tabName?.let(tabs::remove)?.let { tab -> synchronized(tab) { tab.waiter?.complete(null) } }
        return JsonReply(HttpStatusCode.OK, "{}")
    }

    /** An order that landed as the ask ran out still goes to this ask: its page is still listening. */
    private suspend fun lateOrder(tab: Tab, wait: CompletableDeferred<String?>): String? {
        val landed = synchronized(tab) {
            if (tab.waiter === wait) tab.waiter = null
            !wait.complete(null)
        }
        return if (landed) wait.await() else null
    }

    /** The random word a page draws for itself: letters, digits and dashes, and short. */
    private fun ownName(tabName: String): Boolean =
        tabName.length <= MAX_TAB_NAME && tabName.all { it.isLetterOrDigit() || it == '-' }

    private fun deliver(tab: Tab, order: String) {
        synchronized(tab) {
            val waiting = tab.waiter
            tab.waiter = null
            if (waiting == null || !waiting.complete(order)) tab.queue.addLast(order)
        }
    }

    /** The tab the person looked at last, among those still asking. */
    private fun pick(): Pair<String, Tab>? {
        val now = System.currentTimeMillis()
        tabs.entries.removeIf { now - it.value.askedAt > GONE_MS }
        return tabs.entries
            .maxWithOrNull(compareBy<Map.Entry<String, Tab>>({ it.value.focusedAt }, { it.value.askedAt }))
            ?.let { it.key to it.value }
    }

    private fun refused(status: HttpStatusCode, sentence: String): JsonReply =
        JsonReply(status, buildJsonObject { put("error", sentence) }.toString())
}
