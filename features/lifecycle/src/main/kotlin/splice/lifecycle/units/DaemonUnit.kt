// NEW (Oct 25, 2026, splice-lead): splice.service as splice lays it (SpliceUnits). Its directives are the ones the
// daemon has run under since Host Shield laid its unit on Oct 23, so nothing about how the daemon runs changes when
// splice's copy takes over. Two values differ by machine and are written in at install: the JDK the install ran on,
// put first on the daemon's PATH, and the `splice` link the install made. A path the unit could not carry as written
// lays no unit, rather than one that systemd or the shell would read as another path.
package splice.lifecycle.units

import java.nio.file.Path

/** splice.service for an install whose JVM is [javaHome] and whose `splice` link is [splice]. */
public class DaemonUnit(private val javaHome: Path, private val splice: Path) {
    /** The unit's bytes; null where [refusal] says why there are none. */
    public fun bytes(): ByteArray? {
        if (refusal() != null) return null
        val template = javaClass.getResourceAsStream(TEMPLATE)?.use { it.readBytes().decodeToString() } ?: return null
        return SLOT.replace(template) { if (it.value == JAVA_HOME_SLOT) javaHome.toString() else splice.toString() }
            .encodeToByteArray()
    }

    /** Why no unit can be laid for these paths, or null when one can. Each must be absolute and hold no quote,
     *  backslash, dollar, backtick, percent, colon or control character: systemd's quoting and specifiers, the shell
     *  and PATH would each read one of those as something else. */
    public fun refusal(): String? {
        val path = listOf(javaHome, splice).firstOrNull { !it.isAbsolute || it.toString().any(::uncarried) }
            ?: return null
        val shown = path.toString().map { if (it.isISOControl()) '?' else it }.joinToString("")
        return "splice: laid no $DAEMON_UNIT_NAME, since a unit can't carry the path $shown as written; " +
            "the launcher starts the daemon itself"
    }

    private fun uncarried(c: Char): Boolean = c in UNCARRIED || c.isISOControl()
}

private const val TEMPLATE = "/splice/lifecycle/units/splice.service"
private const val JAVA_HOME_SLOT = "@JAVA_HOME@"
private val SLOT = Regex("@JAVA_HOME@|@SPLICE@")
private const val UNCARRIED = "\"'\\\$`%:"
