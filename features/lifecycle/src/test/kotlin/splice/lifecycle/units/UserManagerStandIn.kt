// A stand-in for the systemd user manager, for the tests of splice's own units: a real program on disk that answers
// `systemctl --user` the way the manager would for two units folders, searched in systemd's order (config before data).
package splice.lifecycle.units

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Loads a unit from the first of [config] and [data] that holds it, lists its drop-ins, says which units are active,
 *  and writes every other verb it is given, the ones that act, to its log. */
internal class UserManagerStandIn(private val root: Path) {
    val config: Path = root.resolve("config/systemd/user")
    val data: Path = root.resolve("data/systemd/user")
    val record: Path = root.resolve("state/mcp-hub/units-written")
    private val log: Path = root.resolve("manager.log")
    private val active: Path = root.resolve("manager.active")

    /** The command splice runs for `systemctl --user`. */
    val command: List<String> by lazy { listOf(script().toString(), "--user") }

    /** Each acting verb the manager was given, in order. */
    fun heard(): List<String> = if (Files.exists(log)) Files.readAllLines(log) else emptyList()

    /** The manager reports [name] as active from here on. */
    fun activate(name: String) {
        Files.writeString(active, "$name\n")
    }

    private fun script(): Path {
        val script = root.resolve("systemctl")
        val d = "$"
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |C='$config'; D='$data'; L='$log'; A='$active'
            |[ "${d}1" = --user ] && shift
            |fragment() {
            |  for d in "${d}C" "${d}D"; do
            |    if [ -e "${d}d/${d}1" ] || [ -L "${d}d/${d}1" ]; then printf '%s\n' "${d}d/${d}1"; return; fi
            |  done
            |}
            |dropins() {
            |  for d in "${d}C" "${d}D"; do
            |    for p in "${d}d/${d}1.d"/*.conf; do [ -e "${d}p" ] && printf '%s ' "${d}p"; done
            |  done
            |  echo
            |}
            |case "${d}1" in
            |  show) f=$d(fragment "${d}5")
            |    case "${d}3" in
            |      FragmentPath) printf '%s\n' "${d}f" ;;
            |      DropInPaths) dropins "${d}5" ;;
            |      LoadState) if [ -n "${d}f" ]; then echo loaded; else echo not-found; fi ;;
            |    esac ;;
            |  is-active) n=${d}2; [ "${d}n" = --quiet ] && n=${d}3; grep -qx "${d}n" "${d}A" 2>/dev/null || exit 3 ;;
            |  *) echo "$d*" >>"${d}L" ;;
            |esac
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        return script
    }
}
