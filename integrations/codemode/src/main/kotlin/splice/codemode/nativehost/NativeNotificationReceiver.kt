package splice.codemode.nativehost

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.upstream.codemode.NativeCodeModeNotification
import splice.upstream.codemode.NativeCodeModeNotificationDelivery
import splice.upstream.codemode.NativeCodeModeNotifications
import java.io.IOException

/** Runtime identity stays fixed while the provider resolves the current delivery owner at each notification. */
internal class NativeNotificationReceiver(
    private val sessionKey: String,
    private val cellId: String,
    private val remoteCellId: String,
    private val target: NativeCodeModeNotifications,
) {
    suspend fun deliver(request: JsonObject): NativeCodeModeNotificationDelivery {
        check(JsonScalars.str(request, "cellId") == remoteCellId) { "Native notification changed its cell" }
        val notification = NativeCodeModeNotification(
            sessionKey,
            cellId,
            checkNotNull(JsonScalars.str(request, "callId")),
            JsonScalars.str(request, "text").orEmpty(),
        )
        return try {
            target.deliver(notification)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            rejected()
        } catch (_: RuntimeException) {
            rejected()
        }
    }

    private fun rejected(): NativeCodeModeNotificationDelivery =
        NativeCodeModeNotificationDelivery.Rejected("code-mode notification delegate failed")
}
