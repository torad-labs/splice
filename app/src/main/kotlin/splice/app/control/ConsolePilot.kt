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

/** How long an order waits for the page's answer: a tap on a link waits for the next page, and an act waits for the
 *  calls it started to settle (pilot.js), so this sits well above both. */
private const val ANSWER_MS = 25_000L
private const val MS_PER_SECOND = 1000L

/** A tab names itself with a random word the page draws once; anything longer is not one of ours. */
private const val MAX_TAB_NAME = 64

/** An order carries a few words and a target, never a document. */
private const val MAX_ORDER_CHARS = 65_536

internal class ConsolePilot {

    /**
     * One order on its way to a page; [taken] once a tab's ask carried it away, after which it may have run. Its text
     * carries the order's own id, so two orders are never equal.
     */
    private data class Order(val text: String) {
        @Volatile
        var taken = false
    }

    /** One open console tab: the orders waiting for its next ask, and the ask the daemon is holding open for it. */
    private class Tab {
        val queue = ArrayDeque<Order>()
        var waiter: CompletableDeferred<Order?>? = null

        @Volatile
        var askedAt = 0L

        @Volatile
        var focusedAt = 0L

        /** It let an order go unanswered: other tabs go first until it asks again. */
        @Volatile
        var owes = false
    }

    private val tabs = ConcurrentHashMap<String, Tab>()

    /** An order's answer as the CLI waits on it, and the tab it went to. */
    private data class Awaited(val answer: CompletableDeferred<String>, val tab: Tab)

    private val answers = ConcurrentHashMap<String, Awaited>()
    private val json = Json { ignoreUnknownKeys = true }

    /** The CLI's side: one order for the tab the person looked at last, answered with what the page said. */
    suspend fun order(body: String): JsonReply {
        val long = body.length > MAX_ORDER_CHARS
        val order = if (long) null else JsonScalars.objectOrNull(json, body)
        if (order == null || JsonScalars.str(order, "action").isNullOrBlank()) {
            val why = if (long) {
                "an order is at most 64 KiB; send less at once"
            } else {
                "an order is a JSON object that names an action"
            }
            return refused(HttpStatusCode.BadRequest, why)
        }
        val tab = pick() ?: return refused(
            HttpStatusCode.Conflict,
            "no console tab has asked for orders in the last ${GONE_MS / MS_PER_SECOND} s, so nothing was done. The " +
                "person may have none showing, or one is reconnecting after a restart: wait a few seconds and try " +
                "again, or ask them to show the console.",
        )
        val id = UUID.randomUUID().toString()
        val answer = CompletableDeferred<String>()
        answers[id] = Awaited(answer, tab)
        try {
            val sent = Order(JsonObject(order + ("id" to JsonPrimitive(id))).toString())
            deliver(tab, sent)
            val said = withTimeoutOrNull(ANSWER_MS) { answer.await() }
            return said?.let { JsonReply(HttpStatusCode.OK, it) } ?: unanswered(tab, sent)
        } finally {
            answers.remove(id)
        }
    }

    /** An order no answer came back for. One still waiting in the queue is taken back, so nothing was done; one a tab
     *  carried away may have run. Either way the tab goes behind the others until it asks again, since a closed tab
     *  never does. */
    private fun unanswered(tab: Tab, sent: Order): JsonReply {
        val taken = synchronized(tab) { sent.taken.also { if (!it) tab.queue.remove(sent) } }
        tab.owes = true
        val wait = ANSWER_MS / MS_PER_SECOND
        val sentence = if (taken) {
            "the console took the order but did not answer in $wait s, so it may have run. Run " +
                "`splice console snapshot` before you try again."
        } else {
            "no console tab took the order in $wait s, so nothing was done. The tab may have closed or slept: " +
                "ask the person to show the console, then try again."
        }
        return refused(HttpStatusCode.GatewayTimeout, sentence)
    }

    /** The page's side: its ask for the next order, held open until one comes or [ASK_MS] passes (204). */
    suspend fun next(tabName: String?, focused: Boolean): JsonReply {
        if (tabName == null || !ownName(tabName)) {
            return refused(HttpStatusCode.BadRequest, "a tab asks under its own name")
        }
        val now = System.currentTimeMillis()
        val tab = tabs.computeIfAbsent(tabName) { Tab() }
        tab.askedAt = now
        tab.owes = false
        if (focused) tab.focusedAt = now
        val wait = synchronized(tab) {
            tab.queue.removeFirstOrNull()?.let { return JsonReply(HttpStatusCode.OK, taking(it)) }
            // A newer ask replaces an older one: the page that sent the older one has stopped listening to it.
            tab.waiter?.complete(null)
            CompletableDeferred<Order?>().also { tab.waiter = it }
        }
        val got = withTimeoutOrNull(ASK_MS) { wait.await() } ?: lateOrder(tab, wait)
        return got?.let { JsonReply(HttpStatusCode.OK, it.text) } ?: JsonReply(HttpStatusCode.NoContent, "")
    }

    /** The page's answer to the order it was handed, passed to the CLI that is waiting on it. */
    fun answer(body: String): JsonReply {
        val id = JsonScalars.str(JsonScalars.objectOrNull(json, body), "id")
        val waiting = id?.let(answers::remove)
            ?: return refused(HttpStatusCode.NotFound, "no order is waiting on that answer")
        // A tab that answers is alive, however long the act took since its last ask.
        waiting.tab.askedAt = System.currentTimeMillis()
        waiting.answer.complete(body)
        return JsonReply(HttpStatusCode.OK, "{}")
    }

    /** A tab the person just looked at says so at once, without dropping the ask it holds open. */
    fun focus(tabName: String?): JsonReply {
        tabName?.let(tabs::get)?.focusedAt = System.currentTimeMillis()
        return JsonReply(HttpStatusCode.OK, "{}")
    }

    /** A tab that is closing or hidden takes no more orders. */
    fun leave(tabName: String?): JsonReply {
        tabName?.let(tabs::remove)?.let { tab -> synchronized(tab) { tab.waiter?.complete(null) } }
        return JsonReply(HttpStatusCode.OK, "{}")
    }

    /** An order that landed as the ask ran out still goes to this ask: its page is still listening. */
    private suspend fun lateOrder(tab: Tab, wait: CompletableDeferred<Order?>): Order? {
        val landed = synchronized(tab) {
            if (tab.waiter === wait) tab.waiter = null
            !wait.complete(null)
        }
        return if (landed) wait.await() else null
    }

    /** The random word a page draws for itself: letters, digits and dashes, and short. */
    private fun ownName(tabName: String): Boolean =
        tabName.length <= MAX_TAB_NAME && tabName.all { it.isLetterOrDigit() || it == '-' }

    /** Marks [order] carried away by an ask; called under the tab's lock. */
    private fun taking(order: Order): String {
        order.taken = true
        return order.text
    }

    private fun deliver(tab: Tab, order: Order) {
        synchronized(tab) {
            val waiting = tab.waiter
            tab.waiter = null
            if (waiting != null && waiting.complete(order)) taking(order) else tab.queue.addLast(order)
        }
    }

    /** The tab the person looked at last, among those still asking; one that let an order go unanswered goes last. */
    private fun pick(): Tab? {
        val now = System.currentTimeMillis()
        tabs.values.removeIf { now - it.askedAt > GONE_MS }
        return tabs.values.maxWithOrNull(compareBy<Tab>({ !it.owes }, { it.focusedAt }, { it.askedAt }))
    }

    private fun refused(status: HttpStatusCode, sentence: String): JsonReply =
        JsonReply(status, buildJsonObject { put("error", sentence) }.toString())
}
