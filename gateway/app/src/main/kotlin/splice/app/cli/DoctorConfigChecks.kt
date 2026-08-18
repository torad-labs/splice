// NEW: (split from DoctorCommand.kt, which sits at detekt's 14-function file budget) the doctor
// CONFIGURATION section — does splice.toml exist, does it parse, and is what it says internally
// consistent (provider references resolve, JW-13 port collisions named before a bind error).
package splice.app.cli

import splice.core.topology.TopologyMessages
import java.nio.file.Path

private const val CHECK_TOPOLOGY = "topology"

/** The doctor configuration section as a constructed collaborator (Kotlin style law, 2026-08-15:
 *  main sources carry no top-level functions). Stateless — DoctorCommand builds one and asks it,
 *  inside the same `guarded { }` lambda the section always ran in; the member keeps the old
 *  function's name so the diff at the call site is a receiver insertion. */
internal class DoctorConfigChecks {

    internal fun configurationChecks(topo: DoctorTopology, configPath: Path): List<DoctorCheck> = when (topo) {
        is DoctorTopology.Absent -> listOf(
            DoctorCheck(CHECK_TOPOLOGY, CheckStatus.INFO, "no topology yet at $configPath", "splice init"),
        )
        is DoctorTopology.Broken -> listOf(
            DoctorCheck(
                CHECK_TOPOLOGY,
                CheckStatus.FAIL,
                "$configPath does not parse: ${topo.message}",
                "fix the TOML (compare config/splice.example.toml), or delete it and run: splice init",
            ),
        )
        is DoctorTopology.Parsed -> {
            val topology = topo.topology
            val heads = topology.heads.entries.joinToString(", ") { (k, h) -> "$k → ${h.claude.command ?: k}" }
            val summary = DoctorCheck(
                CHECK_TOPOLOGY,
                CheckStatus.OK,
                "$configPath — ${topology.heads.size} head(s): $heads",
            )
            val brokenRefs = topology.heads.filterValues { it.provider !in topology.providers }.map { (key, head) ->
                DoctorCheck(
                    CHECK_TOPOLOGY,
                    CheckStatus.FAIL,
                    "head '$key' references missing provider '${head.provider}'",
                    "add [providers.${head.provider}] to $configPath or fix the head's provider",
                )
            }
            // JW-13: a duplicate port is a pre-flight FAIL naming both heads (mirrors the
            // wrapper-command collision install validates), not an opaque per-head bind error.
            val portDupes = topology.portCollisions().map { (port, keys) ->
                DoctorCheck(
                    CHECK_TOPOLOGY,
                    CheckStatus.FAIL,
                    TopologyMessages.portCollisionMessage(port, keys),
                    "change one head's port in $configPath",
                )
            }
            listOf(summary) + brokenRefs + portDupes
        }
    }
}
