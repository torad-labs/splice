package splice.provider.codex.stream.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.history.notification.CodeModeHistoryNotifications
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.provider.codex.state.notification.CodeModeNotificationRecords
import splice.upstream.codemode.CodeModeText
import splice.upstream.codemode.NativeCodeModeNotification
import splice.upstream.codemode.NativeCodeModeNotificationDelivery
import splice.upstream.codemode.NativeCodeModeNotifications
import java.util.IdentityHashMap

/** Native delegates resolve the current exact conversation owner instead of retaining a past HTTP sink. */
internal class CodeModeConversationNotifications(private val records: CodeModeNotificationRecords) {
    private val monitor = Any()
    private val identity = CodeModeTurnIdentity()
    private val owners = IdentityHashMap<CodeModeRunContext, CodeModeNotificationOwner>()
    private val preferred = mutableMapOf<Pair<String, String>, CodeModeNotificationToken>()

    suspend fun open(
        context: CodeModeRunContext,
        history: CodeModeHistoryNotifications? = null,
    ): CodeModeNotificationOwner {
        val owner =
            CodeModeNotificationOwner(
                CodeModeNotificationToken(),
                identity.conversationId(context.turn),
                context,
                currentCoroutineContext().job,
                history,
            )
        synchronized(monitor) {
            check(owners[context] == null) { "Notification request owner was already active" }
            owners[context] = owner
        }
        return owner
    }

    suspend fun close(owner: CodeModeNotificationOwner): Unit = withContext(NonCancellable) {
        owner.retire()
        owner.mutex.withLock {
            synchronized(monitor) {
                if (owners[owner.context] === owner) owners.remove(owner.context)
                preferred.entries.removeIf { it.value === owner.token }
            }
        }
    }

    /** An explicit wait claims subsequent delivery for that cell without changing its original exec identity. */
    fun bind(conversation: String, callId: String, context: CodeModeRunContext) = synchronized(monitor) {
        val owner = owners[context]
        if (owner != null && owner.conversation == conversation) preferred[conversation to callId] = owner.token
    }

    fun receiver(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        conversation: String,
    ): NativeCodeModeNotifications {
        bind(conversation, record.origin.outerCallId, context)
        return NativeCodeModeNotifications { notification -> receive(record, conversation, notification) }
    }

    private suspend fun receive(
        record: CodeModeRecord,
        conversation: String,
        notification: NativeCodeModeNotification,
    ): NativeCodeModeNotificationDelivery {
        if (CodeModeText.trim(notification.text).isEmpty()) return NativeCodeModeNotificationDelivery.Delivered
        val refused = NativeCodeModeNotificationDelivery.Rejected(
            "failed to inject exec notify message for cell ${notification.cellId}: no active turn",
        )
        val owner = if (notification.sessionKey == conversation && notification.callId == record.origin.outerCallId) {
            select(record, notification)
        } else {
            null
        }
        return owner?.let { deliver(it, record, notification, refused) } ?: refused
    }

    private suspend fun deliver(
        owner: CodeModeNotificationOwner,
        record: CodeModeRecord,
        notification: NativeCodeModeNotification,
        refused: NativeCodeModeNotificationDelivery.Rejected,
    ): NativeCodeModeNotificationDelivery {
        var persisted = false
        return try {
            coroutineScope {
                val delivery = currentCoroutineContext().job
                owner.deliveries += delivery
                try {
                    owner.mutex.withLock {
                        if (!owner.active || !owner.job.isActive) return@withLock refused
                        if (!records.append(record, owner.context.turn.sessionId, notification)) return@withLock refused
                        owner.history?.inject(owner.context, record)
                        persisted = true
                        owner.context.link.sink.addTextBlock(notification.text)
                        NativeCodeModeNotificationDelivery.Delivered
                    }
                } finally {
                    owner.deliveries -= delivery
                }
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            // Source injection accepts durable history; a later HTTP owner exit cannot unaccept it.
            if (persisted) NativeCodeModeNotificationDelivery.Delivered else refused
        }
    }

    private fun select(record: CodeModeRecord, notification: NativeCodeModeNotification): CodeModeNotificationOwner? =
        synchronized(monitor) {
            val candidates = owners.values.filter {
                it.conversation == notification.sessionKey && it.context.turn.sessionId == record.sessionId &&
                    it.job.isActive
            }
            candidates.firstOrNull { it.token === preferred[notification.sessionKey to notification.callId] }
                ?: candidates.singleOrNull()
        }
}
