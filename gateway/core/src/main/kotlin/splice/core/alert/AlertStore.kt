// NEW: V4-133, FEATURES.md §5/§6 — GET/PUT /api/alerts. "alerts as desktop notifications and an
// optional webhook", one of the table-stakes items the operator kept in. Test send lives on the
// route (POST /api/alerts/test, splice.control.api.AlertRoutes) — this file holds only the
// settings, never a delivery history: FEATURES.md §5 draws that line explicitly ("a 'last fired'
// store is not in the route's purpose").
//
// THE FILE. One JSON document, `alerts.json` under the state dir, same shape as
// splice.core.budget.BudgetStore beside it: SecureFile's temp-then-atomic-move (0600), a backup
// copy first, a memory cache re-read only when the file's mtime moves. PUT replaces the whole
// document (there is only one), so a read degrades to the OFF default on a file that will not
// parse and a write is the recovery path for it, never a second way to lose the setting.
package splice.core.alert

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

/** The state-dir file the alert settings live in. */
public const val ALERTS_FILE: String = "alerts.json"

/** Wire-identical to `webui/src/entities/alert/model/types.ts`. */
@Serializable
public data class AlertSettings(
    val desktop: Boolean = false,
    @SerialName("webhook_url") val webhookUrl: String? = null,
)

/** Off: no desktop notifications, no webhook — the state every install starts in. */
public val defaultAlertSettings: AlertSettings = AlertSettings()

/** A write [AlertStore] refused, with the reason the route reports as a 400. */
public class AlertRefusal(message: String) : IllegalArgumentException(message)

public class AlertStore(private val file: Path) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private var cached: AlertSettings = defaultAlertSettings
    private var cachedStamp: Long? = null

    @Synchronized
    public fun settings(): AlertSettings = load().getOrElse { defaultAlertSettings }

    @Synchronized
    public fun replace(settings: AlertSettings): AlertSettings {
        validate(settings)
        write(settings)
        return settings
    }

    private fun validate(settings: AlertSettings) {
        val url = settings.webhookUrl ?: return
        if (url.isBlank()) throw AlertRefusal("webhook_url must be null to clear it, not blank")
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw AlertRefusal("webhook_url must be an http(s) URL, was '$url'")
        }
    }

    private fun write(settings: AlertSettings) {
        if (Files.exists(file)) {
            SecureFile.writeAtomic0600(file.resolveSibling("${file.fileName}.bak"), Files.readString(file))
        }
        SecureFile.writeAtomic0600(file, json.encodeToString(AlertSettings.serializer(), settings))
        cached = settings
        cachedStamp = stamp()
    }

    private fun load(): Result<AlertSettings> {
        val now = stamp()
        if (now == null) return Result.success(defaultAlertSettings)
        if (now == cachedStamp) return Result.success(cached)
        return Cancellables.runCatchingCancellable {
            json.decodeFromString(AlertSettings.serializer(), Files.readString(file))
        }.onSuccess {
            cached = it
            cachedStamp = now
        }
    }

    private fun stamp(): Long? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- no file yet answers the OFF default, not a failure
        Cancellables.runCatchingCancellable { Files.getLastModifiedTime(file).toMillis() }.getOrNull()
}
