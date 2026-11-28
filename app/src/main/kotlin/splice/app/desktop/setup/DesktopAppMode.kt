// NEW: desktop-full (docs/specs/desktop-full.md, df-app-on and df-diagnostics). The one owner of what splice changes in
// Claude Desktop: its configuration in Desktop's 3P library (DesktopAppLibrary), the programs Desktop runs for splice
// (DesktopPrograms: the credential helper that configuration names, and the launcher), when splice last changed the
// library, and which mode Desktop is in.
// `splice desktop on` puts it in, `off` and `splice uninstall` take out exactly what it put in, and a daemon start keeps
// it current. It never touches the terminal's environment or `~/.claude/settings.json`: Desktop's Code tab runs Claude
// Code over the same `~/.claude` as the terminal, so its settings and sessions stay shared. Used by the daemon's switch
// and, with no daemon, by `splice uninstall`.
package splice.app.desktop.setup

import kotlinx.serialization.json.JsonArray
import splice.app.desktop.catalog.DesktopLineup
import splice.app.desktop.catalog.DoorCatalog
import splice.app.desktop.record.DesktopRecord
import splice.app.desktop.record.DesktopRecords
import splice.core.topology.desktop.DesktopConfig
import splice.core.util.WallClockIso
import java.time.Instant
import java.util.UUID

/** Which mode Claude Desktop is in, as JSON names it. [STANDARD] is the Code-tab door an older splice opened through
 *  Desktop's environment store, still answered until `splice desktop on` replaces it. */
internal enum class DesktopMode(val wire: String) {
    OFF("off"),
    STANDARD("standard"),
    APP("app"),
    OTHER("other"),
    ;

    /** This mode in words, where [other] names the configuration that decides Desktop's provider in [OTHER]. */
    fun said(other: String?): String = when (this) {
        APP -> "on: all of Claude Desktop (Chat, Cowork and the Code tab) runs on splice"
        STANDARD -> STANDARD_SAID
        OTHER -> "another configuration decides Claude Desktop's provider ($other), so it does not use splice"
        OFF -> "off"
    }
}

internal class DesktopAppMode(
    private val records: DesktopRecords,
    private val library: DesktopAppLibrary,
    private val helper: DesktopPrograms,
    /** splice.toml's `[desktop]`, whose big window is the bar a model's 1M variant is published against. */
    private val desktop: DesktopConfig = DesktopConfig(),
    private val now: WallClockIso = WallClockIso { Instant.now().toString() },
) {
    /** Why splice's configuration can't be put in now, with nothing changed, or null when it can. */
    fun refusal(): String? = library.refusal()

    /** The model list Desktop's configuration is given for [lineup], [first] listed first: the one projection every
     *  configuration splice writes reads, so `on` and each republish publish the same 1M variants. */
    fun models(lineup: DesktopLineup, first: String?): JsonArray = DoorCatalog(lineup, desktop).inferenceModels(first)

    /** Keep the applied gateway configuration aligned with the provider catalog, and given splice's policy. */
    fun publish(door: String, lineup: DesktopLineup, first: String?): Boolean =
        library.publish(door, models(lineup, first))

    /** splice's configuration put in for [door] and applied, and the helper laid. Answers the words for what it now
     *  stands in for: empty, or " It replaces <name> while splice is on; …." with a space before it. */
    fun putIn(door: String): String {
        val record = records.get()
        val id = record.appId ?: UUID.randomUUID().toString()
        val (held, replaced) = library.putIn(id, door, helper.lay(door), record.appLibrary)
        records.update { it.copy(appId = id, appLibrary = held, configWrittenAt = now()) }
        return replaced?.let { " It replaces $it while splice is on; `splice desktop off` puts it back." }.orEmpty()
    }

    /** splice's configuration taken out, as it was put in, and its helper removed; whether it was in. */
    fun takeOut(): Boolean {
        val record = records.get()
        val id = record.appId
        val held = record.appLibrary
        if (id == null || held == null) return false
        library.takeOut(id, held)
        helper.remove()
        records.update { it.copy(appLibrary = null, configWrittenAt = now()) }
        return true
    }

    /** At a daemon start with splice's configuration in: the helper laid again, so it follows an upgrade, and the
     *  configuration written again where the door's address in it is no longer [door]. Whether it was rewritten. */
    fun refreshed(door: String): Boolean {
        val record = records.get()
        val id = record.appId
        if (record.appLibrary == null || id == null) {
            if (record.on && library.beside().any { it.onDoor(door) != null }) helper.lay(door)
            return false
        }
        val laid = helper.lay(door)
        if (library.doorOf(id) == door) return false
        library.rewrite(id, door, laid)
        records.update { it.copy(configWrittenAt = now()) }
        return true
    }

    /** Which mode Desktop is in: another configuration (or a managed one) decides over splice's own state; then
     *  splice's configuration applied; then an older splice's environment-store door; else off. */
    fun mode(): DesktopMode {
        val record = records.get()
        return when {
            other(record) != null -> DesktopMode.OTHER
            record.on && record.appLibrary != null && record.appId?.let(library::applies) == true -> DesktopMode.APP
            record.on && record.appLibrary == null -> DesktopMode.STANDARD
            else -> DesktopMode.OFF
        }
    }

    /** The name of the configuration Desktop applies that is not splice's, "a managed configuration" for the managed
     *  file; null when Desktop applies splice's or none. */
    fun other(record: DesktopRecord = records.get()): String? =
        if (library.managed()) MANAGED_NAME else library.appliedOther(record.appId)

    /** Whether Desktop has asked the door since splice last changed its configuration: null outside app mode, since
     *  only app mode's requests say they are Desktop's. Desktop reads its configuration only at launch. */
    fun relaunched(record: DesktopRecord = records.get()): Boolean? {
        if (mode() != DesktopMode.APP) return null
        return record.seenSinceWritten()
    }
}

// How a door an older splice opened through Desktop's environment variables is said.
private const val STANDARD_SAID = "on through the environment variables an older splice had you set in Desktop (the " +
    "Code tab only); `splice desktop on` sets all of Desktop up with nothing to paste"

// What a managed configuration is called where its name is said: splice never opens it.
private const val MANAGED_NAME = "a managed configuration"
