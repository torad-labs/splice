// NEW: (split from DoctorCommand.kt, which sits at detekt's 14-function file budget) the doctor
// CONFIGURATION section — does splice.toml exist, does it parse, and is what it says internally
// consistent (provider references resolve, JW-13 port collisions named before a bind error).
package splice.app.cli

import splice.app.cli.doctor.DoctorProjectPromptChecks
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.prompt.SystemPromptMode
import splice.core.topology.HeadConfig
import splice.core.topology.Topology
import splice.core.topology.TopologyKnobLayer
import splice.core.topology.TopologyMessages
import splice.core.util.EnvReader
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

private const val CHECK_TOPOLOGY = "topology"
private const val REPLACE_FIX =
    "set system_prompt_mode = \"append\" to add your text beside the client's own instructions instead"

internal enum class CheckStatus { OK, INFO, WARN, FAIL }

/** The doctor configuration section as a constructed collaborator (Kotlin style law, 2026-08-15:
 *  main sources carry no top-level functions). Stateless — DoctorCommand builds one and asks it,
 *  inside the same `guarded { }` lambda the section always ran in; the member keeps the old
 *  function's name so the diff at the call site is a receiver insertion. */
internal class DoctorConfigChecks(
    private val localRuntime: DoctorLocalRuntime = DoctorLocalRuntime(),
    /** The same environment the daemon reads, so the ConfigService built below reports the same
     *  rejects the daemon would (V4-109). */
    private val env: EnvReader = EnvReader(System::getenv),
) {
    /** V4-124's project prompt layer rows, in their own file (splice.app.cli.doctor) since V4-156. */
    private val projectPrompts = DoctorProjectPromptChecks(REPLACE_FIX)

    internal fun configurationChecks(
        topo: DoctorTopology,
        configPath: Path,
        live: Boolean = false,
    ): List<DoctorCheck> = when (topo) {
        is DoctorTopology.Absent -> listOf(
            DoctorCheck(CHECK_TOPOLOGY, CheckStatus.INFO, "no topology yet at $configPath", "splice init"),
        )
        is DoctorTopology.Broken -> listOf(
            DoctorCheck(
                CHECK_TOPOLOGY,
                CheckStatus.FAIL,
                // SAFE-RENDER-EXEMPT[2026-08-31]: topo.message is not a throwable — DoctorTopology.Broken is CONSTRUCTED at DoctorCommand.loadTopology from SafeFailureText.render(e) under DR-92, so this renders an already-sanitized String
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
            // v0.4.0 (FEATURES.md §10): local runtimes answer for themselves, in their own words.
            listOf(summary) + brokenRefs + portDupes + ignoredSettingChecks(topology, configPath) +
                stateDirChecks(topology, configPath) + systemPromptChecks(topology) +
                projectPrompts.projectPromptChecks(topology) + localRuntime.localChecks(topology, live)
        }
    }

    /** V4-110: a `[daemon].state_dir` the operator wrote but that cannot be resolved to a path. The
     *  daemon's boot (Main.kt statePathsFor) falls back to the default state dir for such a value —
     *  BY DESIGN, not as a swallowed failure, but that fallback is silent at boot. This row makes it
     *  visible: it names the unusable value AND the default that was used instead, and gives the
     *  operator the next action. WARN, not FAIL: the configuration is legal and the daemon runs; the
     *  override is merely inert. */
    private fun stateDirChecks(topology: Topology, configPath: Path): List<DoctorCheck> {
        val declared = topology.daemon.stateDir?.takeIf { it.isNotBlank() } ?: return emptyList()
        val resolvable = try {
            Paths.get(declared)
            true
        } catch (_: InvalidPathException) {
            false
        }
        if (resolvable) return emptyList()
        val fallback = StatePaths(envReader = env).stateDir
        return listOf(
            DoctorCheck(
                CHECK_TOPOLOGY,
                CheckStatus.WARN,
                "setting 'state_dir' = '$declared' is not a usable path — the daemon uses the " +
                    "default state dir '$fallback' instead",
                "fix state_dir in $configPath (a usable absolute or relative path) or remove it to " +
                    "keep the default",
            ),
        )
    }

    /** V4-109: A KEY THE OPERATOR WROTE AND DID NOT GET. The merge used to drop an unknown key or an
     *  uncoercible value in silence — the knob kept its default and nothing said the operator's line
     *  had never been read, so a typo looked exactly like compliance. This is the SAME ConfigService
     *  the daemon builds (`Daemon.kt:59`, the `DoctorLocalRuntime` idiom), so these rows name what
     *  boot would actually ignore rather than what a second, differently-configured reader thinks.
     *  WARN, not FAIL: the configuration is legal and the daemon runs; the setting is merely inert. */
    private fun ignoredSettingChecks(topology: Topology, configPath: Path): List<DoctorCheck> =
        ConfigService(
            StatePaths(envReader = env),
            headOverrides = TopologyKnobLayer(topology).configOverrides(),
            perHeadOverrides = topology.heads.mapValues { (_, head) -> head.overrides },
            envReader = env,
        ).coerceRejects().map { (where, why) ->
            DoctorCheck(
                CHECK_TOPOLOGY,
                CheckStatus.WARN,
                "setting '$where' is ignored — $why; the knob keeps its default",
                "fix or remove '$where' in $configPath",
            )
        }

    /** V4-36 (operator amendment 2026-09-15): `system_prompt_mode = "replace"` SUBSTITUTES the
     *  client's whole system field, and Claude Code ships its entire operating instruction set in
     *  that field — so the head then runs as a bare model with tools attached. That is the
     *  operator's choice to make, but it must never be a thing they DISCOVER; doctor says it
     *  plainly. WARN, not FAIL: the configuration is legal and deliberate.
     *
     *  Fires only on a head that actually carries a prompt: `replace` with no `system_prompt` or
     *  `system_prompt_file` resolves to null and never reaches the wire, so warning about it would
     *  be noise. The declarations are read straight off the schema rather than resolved, because
     *  resolving would read the prompt FILE and doctor must not throw on an unreadable one. */
    private fun systemPromptChecks(topology: Topology): List<DoctorCheck> = topology.heads
        .filterValues { it.systemPromptMode == SystemPromptMode.REPLACE && carriesPrompt(it) }
        .map { (key, _) ->
            DoctorCheck(
                "system-prompt:$key",
                CheckStatus.WARN,
                "head '$key' sets system_prompt_mode = \"replace\" — the client's own system field is " +
                    "substituted, and Claude Code ships its entire operating instruction set in that " +
                    "field, so this head runs as a bare model with tools attached",
                REPLACE_FIX,
            )
        }

    private fun carriesPrompt(head: HeadConfig): Boolean =
        !head.systemPrompt.isNullOrEmpty() || head.systemPromptFile != null
}
