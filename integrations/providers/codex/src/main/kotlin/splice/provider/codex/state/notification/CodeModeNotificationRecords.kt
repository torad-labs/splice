package splice.provider.codex.state.notification

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.state.CodeModeRecordChanges
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.state.CodeModeWeight
import splice.provider.codex.state.collection.CodeModeRecordKeys
import splice.provider.codex.state.query.CodeModeQueryPreparation
import splice.upstream.codemode.NativeCodeModeNotification

/** A live receiver keeps stable ownership fields, never an evicted record's payload graph. */
internal data class CodeModeNotificationOrigin(
    val key: String,
    val id: String,
    val sessionId: String?,
    val conversation: String,
    val callId: String,
)

/** A notification belongs to its original exec record even when a later request currently receives it. */
internal class CodeModeNotificationRecords(
    private val access: CodeModeRegistryAccess,
    private val records: List<CodeModeRecord>,
    private val changes: CodeModeRecordChanges,
    private val prepare: CodeModeQueryPreparation,
) {
    fun append(
        origin: CodeModeNotificationOrigin,
        session: String,
        notification: NativeCodeModeNotification,
    ): CodeModeRecord? =
        access.withKey(origin.key) {
            prepare(origin.key)
            val record = CodeModeRecordKeys.of(records, origin.key).firstOrNull { it.id == origin.id }
                ?: return@withKey null
            val owned = record.sessionId == session &&
                record.conversationId == notification.sessionKey && record.origin.outerCallId == notification.callId &&
                (record.nativeCellId == null || record.nativeCellId == notification.cellId) &&
                !record.nativeCompleted &&
                record.error == null
            if (!owned) return@withKey null
            val receipt = ResponsesAssistantText.item(notification.text, AssistantPhase.COMMENTARY)
            val output = buildJsonObject {
                put("type", "custom_tool_call_output"); put("call_id", notification.callId)
                put("name", "exec"); put("output", notification.text)
            }
            val delivered = record.deliveredOnly
            val notifications = record.progress.nativeNotifications
            changes.save(
                record,
                undo = {
                    it.deliveredOnly = delivered
                    it.progress.nativeNotifications = notifications
                },
                growthBytes = CodeModeWeight.STORED.json(receipt) + CodeModeWeight.STORED.json(output),
            ) {
                it.deliveredOnly = delivered + receipt
                it.progress.nativeNotifications = notifications + output
            }
            record
        }
}
