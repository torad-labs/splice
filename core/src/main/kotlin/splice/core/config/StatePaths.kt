// PORT-OF: server/src/config.mjs statePaths/stateDir/logsDir @ pre-public-port-baseline — invariants: the
// state ROOT is splice's own (`~/.splice`), the pre-0.4 root is ADOPTED IN PLACE when it is the only
// one on the box, and this file is the ONLY place either root literal may appear (ast-grep wall).
//
// WHY THE ROOT MOVED (V4-177, 2026-09-20). Nobody chose `~/.claude-codex` for splice: it is the
// isolated config dir of the upstream Node proxy this was ported from (.docs/PROVENANCE.md:62-63), and it
// stuck because the header here used to declare the paths an EXTERNAL CONTRACT — "an out-of-repo HUD
// reads codex-usage.json / codex-ratelimit.json / claudex-compact-stats.jsonl byte-identically".
// That consumer is gone, measured rather than assumed: the live statusline (~/.claude/statusline.sh,
// wired at ~/.claude/settings.json:113-116) contains zero references to any of those names, to this
// directory, or to splice at all, and the only readers of the three filenames left anywhere are this
// repo's own UsageTest.kt:162 and CompactTest.kt:182. With the consumer dead the blocker died with
// it, and a product called splice whose state lives under a different project's name is the first
// thing a new user trips on — the repo is going public.
//
// WHY ADOPTION AND NOT A MOVE. Existing installs hold live state under the old root: the mgmt-key,
// daemon.lock, config.json, every head's perf/usage/economics files and V4-174's owner-only trace
// dir. Defaulting to the new root and starting empty would look, to the operator, exactly like a
// daemon that lost every head's history — so when the new root has no state dir and the old one
// does, the old one IS the state dir, unmoved. Nothing is copied, nothing is deleted, and no upgrade
// can half-finish: the decision is re-derived from the filesystem on every construction, so it is
// the same answer for the daemon, the CLI and `splice-launch` without any of them coordinating.
// [origin] and [unmigratedLegacyDir] are that decision made READABLE, because an operator who cannot
// see which root is live cannot tell adoption from data loss (doctor prints both).
//
// The three legacy FILENAMES below (codex-usage.json, codex-ratelimit.json,
// claudex-compact-stats.jsonl) are a SEPARATE question from the directory and deliberately not
// touched here: renaming them would strand usage history for a reason no measurement supports yet.
package splice.core.config

import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths

/** splice's own state root under $HOME. The default since v0.4.0. */
internal const val SPLICE_STATE_HOME: String = ".splice"

/** The pre-0.4 state root, inherited from the upstream port. Read only when it is the one on disk. */
internal const val LEGACY_STATE_HOME: String = ".claude-codex"

/** Points the state dir anywhere; wins over everything but an explicit `baseOverride`. */
internal const val STATE_DIR_ENV: String = "SPLICE_STATE_DIR"

/** The pre-0.4 spelling of [STATE_DIR_ENV]. Still honoured — hermetic tests, `splice-launch` and
 *  anyone scripting it set it — but [STATE_DIR_ENV] is the name that will outlive it. */
internal const val LEGACY_STATE_DIR_ENV: String = "CLAUDEX_STATE_DIR"

private const val STATE_LEAF: String = "state"

// WHY THESE FOUR ARE internal AND NOT public (V4-177, the public-surface ratchet, 2026-09-20).
// Nothing outside :core resolves a state root — that is this file's whole reason to exist and the
// ast-grep wall's subject — so a public spelling would be surface no module consumes, which the
// ratchet gates by name. :core's own tests still read them (a test compilation is associated with
// its main compilation, so `internal` is visible there), which is where the constants earn their
// keep: StateRootTest builds every filesystem shape out of these rather than out of literals, so
// the table cannot quietly test a root the production code stopped using.
//
// :app's tests deliberately do NOT reach across for them. They spell the two roots locally and
// assert StatePaths agrees on a clean home, which fails LOUDLY on divergence rather than importing
// the boundary away — the module law is not a thing a test gets an exemption from.

/** How [StatePaths.stateDir] got its value — the fact doctor reports, so an adopted legacy root is
 *  never mistaken for a daemon that came up with an empty one. */
public enum class StateDirOrigin {
    /** A caller passed the path in (tests, and the control plane's per-fixture paths). */
    OVERRIDE,

    /** [STATE_DIR_ENV] or [LEGACY_STATE_DIR_ENV] named it. */
    ENVIRONMENT,

    /** `$HOME/.splice/state` — the current layout. */
    DEFAULT,

    /** `$HOME/.claude-codex/state` — adopted in place because the current layout has no state dir. */
    ADOPTED_LEGACY,
}

/**
 * What a probe of a candidate state root actually found — THREE answers, not two.
 *
 * `Files.exists` is two-valued by its own contract: "if this method returns false then the file
 * does not exist OR ITS EXISTENCE CANNOT BE DETERMINED". Adoption keyed off that, so a legacy root
 * holding the entire install's history but sitting behind a non-traversable parent (a `sudo` run
 * that left it root-owned, a stale NFS handle, a unit that sets a different HOME) read as ABSENT —
 * and the daemon minted a fresh mgmt-key in a new root while every head's history read as empty.
 * Worse, [StatePaths.unmigratedLegacyDir] gated on the same failing probe, so doctor's state-layout
 * row went silent in precisely the case it was built to speak in, and the box told a clean-install
 * story about an install that was not clean.
 *
 * This is the proven-absence law the module already follows one directory away — `MgmtKeyRead`
 * exists because `String?` conflated absent with unreadable for the mgmt-key, and WrappedHead names
 * it. The decision that picks where the whole product's state lives now follows it too: only
 * [NoSuchFileException] is positive evidence of absence.
 *
 * [Unusable] also covers a NON-DIRECTORY at a root path, which `Files.exists` called true and the
 * shell copies' `[ -d ]` called false — a divergence with no arm in any table until it had one.
 */
internal sealed class RootProbe {
    /** A real directory: usable as a state root. */
    data object Directory : RootProbe()

    /** Proven absent. The only answer that may trigger adoption or a clean start. */
    data object Absent : RootProbe()

    /** There, and not usable — a regular file at the root path, or a probe that failed. NEVER
     *  read as absent, because the difference is the operator's whole history. */
    data class Unusable(val reason: String) : RootProbe()
}

public class StatePaths(
    baseOverride: Path? = null,
    envReader: EnvReader = EnvReader(System::getenv),
    homeDir: Path = Paths.get(System.getProperty("user.home")),
) {
    private val fromEnv: Path? =
        pathOrNull(envReader(STATE_DIR_ENV)) ?: pathOrNull(envReader(LEGACY_STATE_DIR_ENV))

    private val defaultDir: Path = homeDir.resolve(SPLICE_STATE_HOME).resolve(STATE_LEAF)

    private val legacyDir: Path = homeDir.resolve(LEGACY_STATE_HOME).resolve(STATE_LEAF)

    /** Probed only when the answer can matter: an explicit path or an environment variable settles
     *  the root without touching the filesystem, which is what keeps hermetic callers at zero stats. */
    private val probed: Boolean = baseOverride == null && fromEnv == null

    private val defaultProbe: RootProbe? = if (probed) probeRoot(defaultDir) else null

    private val legacyProbe: RootProbe? = if (probed) probeRoot(legacyDir) else null

    /** Adoption needs POSITIVE evidence on both sides: the current root proven absent, and the
     *  pre-0.4 one proven to be a directory. An unreadable or non-directory root satisfies neither,
     *  so it can no longer be silently treated as "not there" — it surfaces as [rootProbeFault]. */
    private val adoptLegacy: Boolean =
        defaultProbe is RootProbe.Absent && legacyProbe is RootProbe.Directory

    public val origin: StateDirOrigin = when {
        baseOverride != null -> StateDirOrigin.OVERRIDE
        fromEnv != null -> StateDirOrigin.ENVIRONMENT
        adoptLegacy -> StateDirOrigin.ADOPTED_LEGACY
        else -> StateDirOrigin.DEFAULT
    }

    public val stateDir: Path = baseOverride ?: fromEnv ?: if (adoptLegacy) legacyDir else defaultDir

    /** The pre-0.4 state dir when it is on disk and is NOT the one in use — history this daemon is
     *  no longer reading. Null whenever there is nothing to say: no legacy dir, or it IS the live
     *  one ([origin] says so), or a caller pointed the state dir somewhere explicitly, in which case
     *  the old root is not "unmigrated", it is simply not theirs. */
    public val unmigratedLegacyDir: Path? = legacyDir.takeIf {
        origin == StateDirOrigin.DEFAULT && legacyProbe is RootProbe.Directory
    }

    /** V4-177: a candidate root that could not be RULED OUT, as a sentence naming the path and what
     *  was wrong — null when both roots gave a definite answer, and null whenever a caller named the
     *  state dir itself (nothing was probed, so there is nothing to report).
     *
     *  This exists because the dangerous case is silent by nature: a daemon that cannot READ the
     *  pre-0.4 root looks exactly like a daemon on a fresh box, and the remedy an operator reaches
     *  for when history appears lost is to delete and re-init, which destroys what was never gone.
     *  Doctor turns this into a WARN naming the path. */
    public val rootProbeFault: String? = listOfNotNull(
        (defaultProbe as? RootProbe.Unusable)?.let { "$defaultDir ${it.reason}" },
        (legacyProbe as? RootProbe.Unusable)?.let { "$legacyDir ${it.reason}" },
    ).joinToString("; ").takeIf { it.isNotEmpty() }

    public val rootDir: Path = stateDir.parent ?: stateDir

    public val logsDir: Path = rootDir.resolve("logs")

    public val configFile: Path = stateDir.resolve("config.json")

    public val mgmtKeyFile: Path = stateDir.resolve("mgmt-key")

    public val daemonLockFile: Path = stateDir.resolve("daemon.lock")

    /** V4-174: where a traced head's day files live (`<head>-YYYY-MM-DD.jsonl`), owner-only. ONE
     *  path for the daemon that writes them and the `splice trace` verb that reads them. */
    public val traceDir: Path = stateDir.resolve("trace")

    /** Per-head stat files. The codex/grok names are a frozen legacy contract kept for CONTINUITY,
     *  not for an external reader (the header's own words — the HUD that justified them is gone, and
     *  renaming them now would strand usage history for no measured gain); new heads derive
     *  `<head>-usage.json` / `<head>-ratelimit.json`. The claudex/claude-grok topology keys MAP to
     *  the legacy names (audit 2026-07-18: key-derived names froze the HUD). */
    public fun usageFile(headKey: String): Path = stateDir.resolve("${legacyStatKey(headKey)}-usage.json")

    public fun ratelimitFile(headKey: String): Path = stateDir.resolve("${legacyStatKey(headKey)}-ratelimit.json")

    public fun quotaFile(headKey: String): Path = stateDir.resolve("${legacyStatKey(headKey)}-quota.json")

    private fun legacyStatKey(headKey: String): String = when (headKey) {
        "codex", "claudex" -> "codex"
        "grok", "claude-grok" -> "grok"
        else -> headKey
    }

    /** IO-006: the codex/claudex (and grok/claude-grok) aliasing above is deliberate migration
     *  continuity — a renamed head keeps its usage history instead of starting a fresh empty file.
     *  But two of [headKeys] mapping to the SAME legacy key means two heads write the same
     *  usage/ratelimit files with no cross-process coordination if both run at once. Same idiom as
     *  Topology.portCollisions: name the collision rather than let it silently race. */
    public fun usageKeyCollisions(headKeys: Collection<String>): Map<String, List<String>> =
        headKeys.groupBy(::legacyStatKey).filterValues { it.size > 1 }

    /** Per-turn perf telemetry JSONL (bottleneck instrument) — additive, not a frozen HUD name. */
    public fun perfStatsFile(headKey: String): Path = stateDir.resolve("$headKey-perf.jsonl")

    /** Hourly token-economics rollup (quota instrument) — additive, not a frozen HUD name. */
    public fun economicsFile(headKey: String): Path = stateDir.resolve("$headKey-economics.json")

    /** The per-session client windows a head learned from status-line posts (ClientWindows): a warm
     *  start across daemon restarts, re-taught by the next post. */
    public fun clientWindowsFile(headKey: String): Path = stateDir.resolve("$headKey-client-windows.json")

    /** 2026-09-22: the models a head's provider endpoint last published (RosterCache), so a start
     *  where the endpoint does not answer keeps the models discovered before instead of shrinking the
     *  picker back to splice.toml's rows — and refusing every turn a session was already running on a
     *  discovered model. */
    public fun modelRosterFile(headKey: String): Path = stateDir.resolve("$headKey-models.json")

    /** Compact-stats JSONL lives in the ROOT dir (not state/) — legacy layout, kept with the names.
     *  The two legacy names are irregular on purpose (claudex-…, claude-grok-…); overridable per head. */
    public fun compactStatsFile(headKey: String, nameOverride: String? = null): Path {
        val name = nameOverride ?: when (headKey) {
            "codex", "claudex" -> "claudex-compact-stats.jsonl"
            "grok" -> "claude-grok-compact-stats.jsonl"
            else -> "$headKey-compact-stats.jsonl"
        }
        return rootDir.resolve(name)
    }

    /** An env var that is set but EMPTY is not an answer: `SPLICE_STATE_DIR=` in a unit file or a
     *  shell export would otherwise resolve to the process working directory and silently put the
     *  mgmt-key and every head's history wherever the daemon happened to start. Fall through to the
     *  next source, exactly as an unset variable does. */
    private fun pathOrNull(raw: String?): Path? = raw?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }

    /** One stat, three answers. A typed catch rather than `runCatching(...).getOrNull()` precisely
     *  because the whole point is to KEEP the distinction the collapse would throw away. */
    private fun probeRoot(dir: Path): RootProbe = try {
        // The ATTRIBUTE-NAME overload, not `BasicFileAttributes::class.java`: a class literal is
        // reflection to kt-no-reflection-in-production, and the string form is the same single stat
        // with the same typed failures — which are the whole point of this function.
        val isDirectory = Files.readAttributes(dir, "basic:isDirectory")["isDirectory"] == true
        if (isDirectory) RootProbe.Directory else RootProbe.Unusable("is not a directory")
    } catch (_: NoSuchFileException) {
        RootProbe.Absent
    } catch (failure: IOException) {
        RootProbe.Unusable("could not be read — ${SafeFailureText.render(failure)}")
    }
}
