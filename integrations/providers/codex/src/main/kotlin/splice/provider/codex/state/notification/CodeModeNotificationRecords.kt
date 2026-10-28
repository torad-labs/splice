package splice.provider.codex.state.notification

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.state.CodeModeRecordChanges
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.state.CodeModeWeight
import splice.upstream.codemode.NativeCodeModeNotification

/** A notification belongs to its original exec record even when a later request currently receives it. */
internal class CodeModeNotificationRecords(
    private val access: CodeModeRegistryAccess,
    private val records: List<CodeModeRecord>,
    private val changes: CodeModeRecordChanges,
) {
    fun append(record: CodeModeRecord, session: String, notification: NativeCodeModeNotification): Boolean =
        access.withKey(record.key) {
            val owned = records.any { it === record } && record.sessionId == session &&
                record.conversationId == notification.sessionKey && record.origin.outerCallId == notification.callId &&
                (record.nativeCellId == null || record.nativeCellId == notification.cellId) &&
                !record.nativeCompleted &&
                record.error == null
            if (!owned) return@withKey false
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
            true
        }
}
