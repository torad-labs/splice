// NEW: upgrade-compat (Nov 23, 2026). What boot serves past (ConfigRead.Ready.warnings): a value a 0.6.4 splice.toml left
// on a head that its provider should hold for the gateway, which the gateway serves from the head meanwhile, and a
// retired or misplaced setting splice no longer reads. Each is said in daemon.log once the logger exists, with the verb
// that offers its fix, and a setting splice itself saved in its state folder is cleared here, after a copy, under the
// daemon lock (SavedSettingsPrune): splice's own file never keeps its daemon from starting.
package splice.app.daemon

import splice.core.config.StatePaths
import splice.core.topology.TopologyFinding
import splice.core.util.LogSink
import splice.topology.SavedSettingsPrune

internal class BootWarnings(private val log: LogSink) {
    fun say(warnings: List<TopologyFinding>, statePaths: StatePaths) {
        if (warnings.isEmpty()) return
        warnings.forEach { log("[config] warning: ${it.text()}\n") }
        log("[config] splice serves past ${warnings.size} warning(s); `splice check-config --fix` offers each fix\n")
        SavedSettingsPrune(statePaths.configBackupsDir).prune(warnings).forEach { log(it) }
    }
}
