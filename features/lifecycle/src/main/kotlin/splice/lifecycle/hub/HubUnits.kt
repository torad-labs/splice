// NEW: the MCP hub's three systemd units, for a machine its host supervisor has not given them (Oct 12). The
// bytes under resources/splice/lifecycle/hub are the host supervisor's canonical units, byte for byte: the supervisor's
// apply owns them wherever it is installed, and this copy only covers a machine without it.
// Oct 13 (splice-lead, the host's carve-out): `splice setup` turns the hub on when its share answer is yes, but
// only over units splice wrote itself. Splice's bytes are the host's byte for byte, so a byte match cannot tell them
// apart: each unit splice writes is recorded with its SHA-256 in [record], in splice's own state, and a unit counts as
// splice's only while it is recorded, still hashes to the record, and is what systemd loads, from this folder with no
// drop-in.
// Oct 13 (splice-lead): `splice upgrade` reloads a running front onto the programs it swapped in under the same
// rule; over units anyone else placed it prints the reload for their owner (reloadFront).
// Oct 13 (hostshield-lead's ruling, its manifest's splice-mcp-front-continuity layer): hostshield owns only the units'
// bytes and containment, and the front's programs and their upgrade are splice's. So reloadFront also reloads the
// canonical unit hostshield wrote, through that unit's own ExecReload (USR2, the in-place re-exec), never a restart, and
// it still only prints the command over a unit whose bytes are not the canonical ones.
package splice.lifecycle.hub

import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import splice.lifecycle.start.UserManagerBus
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit

/** Creates each unit in [unitDir], the user's own systemd/user directory, only where it is positively absent, and
 *  never touches one that is there.
 *
 *  POSITIVELY ABSENT means two things at once: nothing of that name in [unitDir], no unit file, symlink (a masked
 *  unit is one), or drop-in directory; and the user manager answering, with exit 0, that the unit's load state is
 *  `not-found`. A systemctl that fails, times out or cannot reach the manager is not an answer, so nothing is
 *  created, and the daemon keeps its in-process MCP path. The write is create-only, so a unit that appears between
 *  the check and the write is kept. Nothing is enabled or started by [install]; [enable] is setup's, over splice's own
 *  units only. */
public class HubUnits(private val unitDir: Path, private val record: Path, private val output: TerminalOutput) {
    public fun install() {
        val written = UNITS.filter { name -> absent(name) && create(name) }
        if (written.isEmpty()) return
        val kept = recorded()
        val hashes = kept + written.associateWith { template(it)?.let(::sha256).orEmpty() }
        SecureFile.writeAtomic0600(record, hashes.entries.joinToString("") { "${it.value}  ${it.key}\n" })
        systemctl(listOf("daemon-reload"))
        written.forEach { output.line("splice: wrote $it to $unitDir, since no unit of that name was installed") }
    }

    /** Enables and starts the front's socket when every hub unit is splice's own, and says what it did. Over units
     *  splice did not write, it touches nothing and prints the command. With no hub unit at all, it says nothing. */
    public fun enable() {
        if (UNITS.none { Files.exists(unitDir.resolve(it), LinkOption.NOFOLLOW_LINKS) }) return
        if (!UNITS.all(::ours)) {
            output.line(
                "splice: the MCP hub's units here were not written by splice, so setup leaves them as they are; " +
                    "to turn the hub on, run: $ENABLE_LINE",
            )
            return
        }
        if (systemctl(ENABLE) != null) {
            output.line("splice: turned the MCP hub on (enabled and started $SOCKET)")
        } else {
            output.line("splice: the MCP hub's socket did not start; run: $ENABLE_LINE")
        }
    }

    /** After `splice upgrade` swapped the hub's programs: a running front takes the new one by reload, never a
     *  restart, when every hub unit is splice's own or the front service systemd loads is the canonical unit. Over any
     *  other unit it touches nothing and prints the command for its owner to apply. Where no front unit is loaded at
     *  all, it says nothing. */
    public fun reloadFront() {
        if (!UNITS.all(::ours) && !canonical(SERVICE)) {
            if (systemctl(LOAD_STATE + SERVICE) != LOAD_STATE_NOT_FOUND) {
                output.line(
                    "splice: the MCP hub's units here were not written by splice, so their owner reloads the front " +
                        "onto the new program: $RELOAD_LINE",
                )
            }
            return
        }
        if (systemctl(listOf("is-active", "--quiet", SERVICE)) == null) return
        output.line(
            if (systemctl(listOf("reload", SERVICE)) != null) {
                "splice: reloaded $SERVICE onto the new program"
            } else {
                "splice: $SERVICE did not reload, so it keeps its old program until it next starts; run: $RELOAD_LINE"
            },
        )
    }

    /** Splice wrote [name] here, it still holds those bytes, and systemd loads it from this file with no drop-in. */
    private fun ours(name: String): Boolean {
        val file = unitDir.resolve(name)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.exists(unitDir.resolve("$name.d"), LinkOption.NOFOLLOW_LINKS)) return false
        val hash = Cancellables.runCatchingBestEffort { sha256(Files.readAllBytes(file)) }.getOrNull()
        return hash != null && hash == recorded()[name] && hash == template(name)?.let(::sha256) &&
            shown("FragmentPath", name) == file.toString() &&
            shown("DropInPaths", name) == ""
    }

    /** The unit systemd loads for [name] is the canonical one byte for byte, with no drop-in, whoever placed it: its
     *  ExecReload is then the USR2 in-place reload, never a restart. */
    private fun canonical(name: String): Boolean {
        val loaded = shown("FragmentPath", name)?.takeIf { it.isNotEmpty() }
            ?: return false
        val hash = Cancellables.runCatchingBestEffort { sha256(Files.readAllBytes(Path.of(loaded))) }.getOrNull()
        return hash != null && hash == template(name)?.let(::sha256) &&
            shown("DropInPaths", name) == ""
    }

    /** Unit name to the SHA-256 splice recorded on writing it; empty when nothing is recorded or it is unreadable. */
    private fun recorded(): Map<String, String> = Cancellables.runCatchingBestEffort {
        if (!Files.exists(record)) return@runCatchingBestEffort emptyMap()
        Files.readAllLines(record).mapNotNull { line ->
            line.split("  ", limit = 2).takeIf { it.size == 2 }?.let { (hash, unit) -> unit to hash }
        }.toMap()
    }.getOrNull().orEmpty()

    private fun template(name: String): ByteArray? =
        javaClass.getResourceAsStream("$RESOURCES/$name")?.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun absent(name: String): Boolean =
        !Files.exists(unitDir.resolve(name), LinkOption.NOFOLLOW_LINKS) &&
            !Files.exists(unitDir.resolve("$name.d"), LinkOption.NOFOLLOW_LINKS) &&
            systemctl(LOAD_STATE + name) == LOAD_STATE_NOT_FOUND

    private fun create(name: String): Boolean {
        val bytes = template(name) ?: return false
        Files.createDirectories(unitDir)
        return Cancellables.runCatchingBestEffort {
            Files.write(unitDir.resolve(name), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }.isSuccess
    }

    /** One property of the unit systemd loads for [name], as `systemctl show` prints its value. */
    private fun shown(property: String, name: String): String? =
        systemctl(listOf("show", "-p", property, "--value", name))

    /** The command's trimmed stdout when it exits 0 within the bound; null for any other outcome. */
    private fun systemctl(args: List<String>): String? = Cancellables.runCatchingBestEffort {
        val process = UserManagerBus.supply(ProcessBuilder(listOf("systemctl", "--user") + args))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val done = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!done) process.destroyForcibly()
        out.trim().takeIf { done && process.exitValue() == 0 }
    }.getOrNull()
}

private val UNITS = listOf("app-mcp.slice", "splice-mcp-front.socket", "splice-mcp-front.service")
private const val RESOURCES = "/splice/lifecycle/hub"
private const val LOAD_STATE_NOT_FOUND = "not-found"

// why: the bound on one systemctl --user call; a call still running past it is killed and reads as no answer.
private const val TIMEOUT_MS = 15_000L

private const val SOCKET = "splice-mcp-front.socket"
private val ENABLE = listOf("enable", "--now", SOCKET)
private const val ENABLE_LINE = "systemctl --user enable --now $SOCKET"
private const val SERVICE = "splice-mcp-front.service"
private val LOAD_STATE = listOf("show", "-p", "LoadState", "--value")
private const val RELOAD_LINE = "systemctl --user reload $SERVICE"
