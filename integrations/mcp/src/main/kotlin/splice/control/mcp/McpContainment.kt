// NEW: V4-147 (2026-09-19) — a hosted MCP child is CAPPED by a slice the HOST declares, and left
// SELECTABLE by every reaper, instead of inheriting splice's own unkillability.
//
// MEASURED ON THIS BOX (2026-09-18): splice's MainPID runs at oom_score_adj -1000, and its three live
// hosted children were at -1000 too — by pure inheritance, nothing asked for it — while
// splice.service's MemoryHigh, MemoryMax and MemorySwapMax were all infinity. V4-146 moves ~14 GB of
// stdio servers under this host, which would turn reclaimable memory into uncapped AND unreclaimable
// memory on a 59.2 GB box; the 2026-07-21 Warp incident is that exact shape (adj -500, no cap, 45 GB,
// both killers forbidden to touch it). THE CAP IS THE LOAD-BEARING HALF: an adj only orders victims
// once the box is already in trouble.
//
// THE SPLIT OF OWNERSHIP, stated as a contract rather than as one host's arrangement: THE HOST owns
// [APP_MCP_SLICE] and the ceiling on it, because a cap is a policy about the whole box and only the
// box's owner can size it; SPLICE owns the oom_score_adj of each child it spawns, because its
// launcher is the only component that knows which pid is a hosted server and which is splice itself.
// Neither half is optional and neither can be done by the other: a cap with no adj leaves the child
// unkillable inside its cap, and an adj with no cap orders victims in a box already in trouble.
//
// splice therefore ships NO unit and NO slice file. It asks systemd for the cap at spawn time
// ([placed] below) and says so in the log when the answer is that there is none, which is the only
// honest thing to do about a requirement the host has not met.
//
// TWO THINGS MEASURED HERE, and both shape this class:
//   - `systemd-run --user --scope` EXECS the command (verified: the spawned pid is the command's own,
//     with systemd-run gone), so a placed child keeps splice's Process handle, its stdio pipes and its
//     kill semantics — there is no wrapper process in between to orphan it;
//   - systemd materializes a slice ON DEMAND: `systemctl --user show app-mcp.slice` answers
//     LoadState=loaded for a slice nobody declared, with MemoryMax=infinity. So placement asks for the
//     CAP and never for existence, or an uncapped cgroup would read as containment — the campaign's
//     false-green signature.
package splice.control.mcp

import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** A systemd slice's memory ceiling, as `systemctl --user show <slice> -p MemoryMax --value` prints
 *  it: "infinity" for a slice with no cap, null when systemd could not be asked at all. */
public fun interface SliceMemoryCap {
    public operator fun invoke(slice: String): String?
}

/** Writes a spawned child's oom_score_adj and READS IT BACK — the read is the assertion, because a
 *  write that lands on another value still reports success. Null when it could not be written. */
public fun interface OomScoreAdjWrite {
    public operator fun invoke(pid: Long, value: Int): Int?
}

/** Where a hosted child runs and how killable it is. Both halves are per-spawn, and neither ever
 *  touches splice's own process: [protect] writes exactly one pid, the child's. */
public class McpContainment(
    private val log: LogSink,
    private val slice: String = APP_MCP_SLICE,
    private val adj: Int = HOSTED_ADJ,
    private val cap: SliceMemoryCap = SystemdSliceCap(),
    private val oom: OomScoreAdjWrite = ProcOomScoreAdj(),
) {
    private val uncappedSaid = AtomicBoolean(false)

    /** The argv to spawn: inside the declared slice when that slice CARRIES A CAP, else the command as
     *  it stands. Containment that does not exist is said once, never faked. */
    public fun placed(command: List<String>): List<String> {
        val ceiling = cap(slice)
        if (ceiling == null || ceiling == UNCAPPED) {
            if (uncappedSaid.compareAndSet(false, true)) {
                log(
                    "[mcp-host] hosted children are not capped: ${LogSafe.str(slice)} declares no " +
                        "memory ceiling " +
                        "so they run in splice's own cgroup — declare a memory ceiling on that " +
                        "slice to contain them\n",
                )
            }
            return command
        }
        return listOf("systemd-run", "--user", "--scope", "--quiet", "--slice=$slice", "--") + command
    }

    /** Off splice's inherited -1000, so every reaper can select the child. A value that did not land
     *  is said in words: this is the half that has no gauge of its own. */
    public fun protect(name: String, process: Process) {
        val landed = oom(process.pid(), adj)
        if (landed != adj) {
            log(
                "[mcp-host] ${LogSafe.str(name)}: the child's oom_score_adj is " +
                    "${LogSafe.str(landed?.toString() ?: "unreadable")}, not ${LogSafe.str(adj.toString())} — " +
                    "it keeps splice's own protection from the out-of-memory killers\n",
            )
        }
    }
}

/** `systemctl --user show <slice> -p MemoryMax --value`; null when systemd is not there to ask. */
public class SystemdSliceCap : SliceMemoryCap {
    override fun invoke(slice: String): String? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-19 (V4-147): a box with no systemd, or one where the call fails, is a box whose slices cannot be read at all; null is the complete answer and McpContainment says it out loud.
        Cancellables.runCatchingCancellable {
            val process = ProcessBuilder("systemctl", "--user", "show", slice, "-p", "MemoryMax", "--value")
                .redirectErrorStream(false)
                .start()
            val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.waitFor() == 0) value.takeIf { it.isNotEmpty() } else null
        }.getOrNull()
}

/** `/proc/<pid>/oom_score_adj`, written and read back. A RAISE off an inherited -1000 is unprivileged
 *  (verified on this box), which is the only direction this writes. */
public class ProcOomScoreAdj(private val procRoot: Path = Path.of("/proc")) : OomScoreAdjWrite {
    override fun invoke(pid: Long, value: Int): Int? {
        val file = procRoot.resolve(pid.toString()).resolve("oom_score_adj")
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-19 (V4-147): a child that died between spawn and this write, or a kernel without /proc, has no adj to read back; null IS the failure and protect() reports it in words rather than swallowing it.
        return Cancellables.runCatchingCancellable {
            Files.writeString(file, value.toString())
            Files.readString(file).trim().toInt()
        }.getOrNull()
    }
}

/** The slice the host is asked to declare for hosted MCP servers. The name follows systemd's own
 *  dash-nesting, so it sits under `app.slice` and inherits whatever backstop the box puts there
 *  while carrying its own tighter cap. splice never creates it — see the ownership split above. */
public const val APP_MCP_SLICE: String = "app-mcp.slice"

// why: systemd's own word for "no ceiling" in `show -p MemoryMax --value`, which is what a slice
// nobody declared answers — the value that must never read as containment.
private const val UNCAPPED = "infinity"

// why: the adj every hosted child is raised to. Strictly above the -400 floor an out-of-memory reaper
// is conventionally told to skip, so every reaper can still select it — the whole point of the raise —
// while staying below an ordinary user process (0), because killing an MCP server destroys a
// capability the session cannot respawn by itself rather than a cache.
public const val HOSTED_ADJ: Int = -100
