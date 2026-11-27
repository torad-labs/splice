package splice.app.mods.run

import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

private const val PS_TIMEOUT_MS = 3000L // why: ps is the one external helper, and a hung one must not hold a stop
private const val STAT_STATE_INDEX = 0 // why: after the command's ")" /proc/<pid>/stat runs state, parent, group
private const val STAT_GROUP_INDEX = 2
private const val PS_FIELDS = 3 // why: pid, group, state

/** A process told apart from every later user of its pid by the moment it started. */
internal data class ModIdentity(val pid: Long, val birth: Instant)

/**
 * The process group of every live process on the machine, as pid to group, or null when the table could not be read
 * whole. Null is never an answer of "nobody": a caller that cannot read the table must not conclude a group is gone.
 * Zombies are not listed: a process that has exited holds nothing.
 */
internal fun interface ModGroupInspector {
    fun groups(): Map<Long, Long>?

    /** False on a system whose table splice cannot read: no mod is started there. */
    val supported: Boolean get() = true
}

internal object ModProcessIdentity {
    fun of(handle: ProcessHandle): ModIdentity? =
        handle.info().startInstant().orElse(null)?.let { ModIdentity(handle.pid(), it) }

    /** The live process that still has this pid AND this birth; anything else is another process or none. */
    fun open(identity: ModIdentity): ProcessHandle? =
        ProcessHandle.of(identity.pid).orElse(null)?.takeIf { it.isAlive && of(it) == identity }
}

/**
 * Reads the process table where splice can do it with certainty. Linux reads /proc. macOS asks one `ps`, bounded by
 * [PS_TIMEOUT_MS]. Every other system answers null, so a mod is refused there instead of being started with processes
 * nobody could stop. A table that does not list this very process is not whole and answers null as well.
 */
internal class ModProcessTable(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val psPath: String = "/bin/ps",
    private val log: LogSink = LogSink {},
) : ModGroupInspector {
    private val blanks = Regex("\\s+")

    override val supported: Boolean get() = osName.startsWith("Linux") || osName.startsWith("Mac")

    override fun groups(): Map<Long, Long>? {
        val read = Cancellables.runCatchingCancellable {
            when {
                osName.startsWith("Linux") -> fromProc()
                osName.startsWith("Mac") -> fromPs()
                else -> null
            }
        }.onFailure { log("[mods] cannot read the process table: ${SafeFailureText.render(it)}\n") }
        return read.getOrNull()?.takeIf { ProcessHandle.current().pid() in it }
    }

    /** `ps -o pid=,pgid=,stat=` lines to pid and group, zombies left out. */
    fun parse(text: String): Map<Long, Long> = buildMap {
        for (line in text.lineSequence()) {
            val fields = line.trim().split(blanks)
            val pid = fields.getOrNull(0)?.toLongOrNull()
            val group = fields.getOrNull(1)?.toLongOrNull()
            val zombie = fields.getOrNull(PS_FIELDS - 1)?.startsWith("Z") ?: true
            if (pid != null && group != null) {
                if (!zombie) put(pid, group)
            }
        }
    }

    private fun fromProc(): Map<Long, Long> = ProcessHandle.allProcesses().use { all ->
        buildMap { all.forEach { handle -> groupFromStat(handle.pid())?.let { put(handle.pid(), it) } } }
    }

    // A process that exits between the listing and this read is simply not there any more.
    private fun groupFromStat(pid: Long): Long? = try {
        val fields = Files.readString(Path.of("/proc/$pid/stat")).substringAfterLast(')').trim().split(' ')
        fields.getOrNull(STAT_GROUP_INDEX)?.toLongOrNull()?.takeUnless { fields[STAT_STATE_INDEX] in "ZX" }
    } catch (_: IOException) {
        null
    }

    private fun fromPs(): Map<Long, Long>? {
        val ps = ProcessBuilder(psPath, "-A", "-o", "pid=,pgid=,stat=")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        // A ps that outlives its budget is killed, which also ends the blocking read below.
        ps.onExit().orTimeout(PS_TIMEOUT_MS, TimeUnit.MILLISECONDS).whenComplete { _, failure ->
            if (failure != null) ps.destroyForcibly()
        }
        val text = ps.inputStream.bufferedReader().use { it.readText() }
        return if (ps.waitFor() == 0) parse(text) else null
    }
}
