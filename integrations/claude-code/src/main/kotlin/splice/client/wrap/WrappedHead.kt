// NEW: V4-129 — the default-command shim (FEATURES.md 4.12 "Wrap"). The operator's plain `claude`
// on PATH becomes a splice launcher over the vanilla config, which this file never writes (V4-445): a
// wrapped launch runs Claude Code with no CLAUDE_CONFIG_DIR, so it reads the operator's real ~/.claude.json
// and ~/.claude exactly as a plain `claude` does, and the head's own settings ride the launch (LaunchService).
// Wrap changes one symlink, its state file and the launcher-owner locator. Three hazards this file exists to close:
//   - SELF-EXEC: app/src/main/dist/bin/splice-launch execs its recipe's argv[0] by resolving it through PATH, and
//     LaunchService plants the bare string "claude" there. The moment `claude` on PATH IS the shim,
//     every head's launch (not only the wrapped one) would resolve argv[0] back to the shim that is
//     currently running, recursing forever. WrapStateStore is the one fact LaunchService reads on
//     every launch (WrapStateRead) to plant the REAL absolute binary path instead — written BEFORE
//     the symlink swap below, so a crash between the two leaves LaunchService already answering the
//     absolute path while `claude` on PATH is still, in fact, untouched (harmless: same target,
//     spelled absolutely) rather than the reverse ordering, which would leave every head resolving
//     a shim with nothing telling them not to.
//   - THE UPDATER: `~/.local/bin/claude` is a symlink Claude Code owns, and it re-points it on every release
//     (2.1.282 to .285 in four days), which replaces the shim and pins the recorded binary to a version the
//     updater then deletes. [WrappedHead.reconcile] notices, records the live binary and puts the shim
//     back; the daemon runs it at start and on a watch of the bin directory (WrapGuard).
//   - THE DAEMON'S OWN SWAP: unwrap swaps the same symlink, and the watch would read that as an update and
//     wrap again. wrap, unwrap and reconcile therefore take one lock, and unwrap clears the state inside it.
// "no pool, no isolation, no un-link on a wrapped head" (the row title): this class does not touch
// Topology, ManagedHead or the account pool — it is a self-contained shim-and-state mechanism.
// DTOs, read seams and outcome types live in WrappedHeadTypes.kt (concentration, 2026-09-20). Same
// package, same FQCNs.
package splice.client.wrap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.Keys
import splice.client.SymlinkOp
import splice.core.config.InstallPaths
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.core.util.FileTightening
import splice.core.util.JsonScalars
import splice.core.util.PathProbe
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.isSymbolicLink

private const val CLAUDE_COMMAND = "claude"
private const val SHIM_NAME = "splice-launch"
private const val WRAP_STATE_FILE = "claude-head-wrap.json"

/** One lock for every state-changing wrap operation in this JVM (the daemon's): see the file header. */
private val WRAP_LOCK = Any()

/** The one fact [splice.launch.recipe.LaunchService] must read on every launch (see file header). A file,
 *  not in-memory state: the daemon's LaunchService is constructed once at boot while wrap/unwrap are
 *  per-request actions, possibly from a different process (`splice` CLI) — only a file both can
 *  reach keeps them from disagreeing. */
public class WrapStateStore(
    private val file: Path = StatePaths().stateDir.resolve(WRAP_STATE_FILE),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /** Absent only when the file is not there; a file that is there and cannot be used is [StoredWrap.Unreadable]. */
    public fun read(): StoredWrap = try {
        parse(Files.readString(file))
    } catch (_: NoSuchFileException) {
        StoredWrap.Absent(file)
    } catch (_: AccessDeniedException) {
        StoredWrap.Unreadable(file, "is not readable by this user")
    } catch (_: CharacterCodingException) {
        StoredWrap.Unreadable(file, "is not valid text")
    } catch (_: IOException) {
        StoredWrap.Unreadable(file, "could not be read")
    }

    private fun parse(text: String): StoredWrap {
        val obj = JsonScalars.objectOrNull(json, text)
            ?: return StoredWrap.Unreadable(file, "is not a JSON object")
        val realBinaryPath = JsonScalars.str(obj, "real_binary_path")
            ?: return StoredWrap.Unreadable(file, "names no real_binary_path")
        return StoredWrap.Present(
            WrapState(
                realBinaryPath = realBinaryPath,
                shadowedSymlinkTarget = JsonScalars.strOrEmpty(obj["shadowed_symlink_target"]),
                shimPath = JsonScalars.strOrEmpty(obj["shim_path"]),
                settingsBackupPath = JsonScalars.strOrEmpty(obj["settings_backup_path"]),
                claudeJsonBackupPath = JsonScalars.strOrEmpty(obj["claude_json_backup_path"]),
                wrappedAtEpochMillis = JsonScalars.long(obj, "wrapped_at_epoch_millis") ?: 0L,
            ),
        )
    }

    public fun write(state: WrapState) {
        val body = buildJsonObject {
            put("real_binary_path", state.realBinaryPath)
            put("shadowed_symlink_target", state.shadowedSymlinkTarget)
            put("shim_path", state.shimPath)
            if (state.settingsBackupPath.isNotBlank()) put("settings_backup_path", state.settingsBackupPath)
            if (state.claudeJsonBackupPath.isNotBlank()) put("claude_json_backup_path", state.claudeJsonBackupPath)
            put("wrapped_at_epoch_millis", state.wrappedAtEpochMillis)
        }
        SecureFile.writeAtomic0600(file, json.encodeToString(JsonObject.serializer(), body) + "\n")
    }

    /** Locate this wrap independently of the caller's HOME and user-manager bus. */
    public fun recordLauncherOwner(home: Path, shim: Path, profile: Map<String, String>) {
        val owner = shim.toRealPath().resolveSibling("splice-launch-owner.json")
        val body = buildJsonObject {
            put("home", home.toAbsolutePath().normalize().toString())
            put("state_dir", file.toAbsolutePath().normalize().parent.toString())
            if (profile.isNotEmpty()) put("selectors", ownerSelectors(home, profile))
        }.toString() + "\n"
        if (!Files.exists(owner, NOFOLLOW_LINKS) || Files.readString(owner) != body) {
            SecureFile.writeAtomic0600(owner, body)
        }
        when (val access = SecureFile.ownerOnlyFile(owner)) {
            is FileTightening.Open -> error("launcher owner record is not owner-only: ${access.why}")
            else -> Unit
        }
    }

    private fun ownerSelectors(home: Path, profile: Map<String, String>): JsonObject = buildJsonObject {
        profile.forEach { (name, value) ->
            val selected = if (name == "SPLICE_CONFIG" || name == "XDG_CONFIG_HOME") {
                (if (value.startsWith("~/")) home.resolve(value.substring(2)) else Paths.get(value))
                    .toAbsolutePath().normalize().toString()
            } else {
                value
            }
            put(name, selected)
        }
    }

    /** Best-effort: a failed delete only leaves a stale file a later wrap's write() will overwrite anyway. */
    public fun clear() {
        Cancellables.discard(
            Cancellables.runCatchingCancellable { Files.deleteIfExists(file) },
            "wrap-state clear is best-effort: a file left behind is overwritten by the next wrap",
        )
    }
}

// WrapState, WrapStateRead, ClaudeHeadStatus, WrapResult and UnwrapResult live in
// WrappedHeadTypes.kt (concentration split, 2026-09-20) — same package, same FQCNs.

/** Puts a pre-V4-445 wrap's backed-up operator files back. */
private object WrapBackups {
    fun restore(target: Path, backupFrom: Path) {
        if (!Files.exists(backupFrom, NOFOLLOW_LINKS)) {
            Cancellables.discard(
                Cancellables.runCatchingCancellable { Files.deleteIfExists(target) },
                "restoring to absence: the pre-wrap state genuinely had nothing here",
            )
            return
        }
        Files.move(backupFrom, target, REPLACE_EXISTING, ATOMIC_MOVE)
    }
}

/** The pre-flight read [WrappedHead.wrap] needs before it writes anything — split out so neither
 *  function's return count trips the wall (Kotlin style law: ReturnCount <= 3). */
private sealed class WrapPreflight {
    data class Ready(val shadowedTarget: String, val realBinaryPath: String) : WrapPreflight()
    data class Refused(val reason: String) : WrapPreflight()
}

/** V4-129: wrap/unwrap orchestration — see file header for the two hazards this closes.
 *
 *  It is also the [WrapStateRead] every launch plants argv[0] from, so the fact wrap WRITES and the
 *  fact a launch READS come from one object and one state file. */
public class WrappedHead(
    private val home: Path,
    private val installPaths: InstallPaths = InstallPaths(),
    private val stateStore: WrapStateStore = WrapStateStore(),
    private val symlink: SymlinkOp = SymlinkOp { link, target -> Files.createSymbolicLink(link, target) },
    private val now: WallClock = WallClock(System::currentTimeMillis),
) : WrapStateRead {
    private val commandPath: Path get() = installPaths.binDir.resolve(CLAUDE_COMMAND)
    private val shimPath: Path get() = installPaths.shareDir.resolve(SHIM_NAME)
    private val vanillaDir: Path get() = home.resolve(Keys.VANILLA_DIR)

    /** The real claude binary while wrap is in place (its state file is the proof), else null. A recorded binary
     *  the updater has since deleted, or one a newer installed version has passed, is put right first
     *  ([reconcile]), so a launch never execs a dead path or an old release. */
    override fun realBinaryPath(): String? {
        val state = stateStore.read().state ?: return null
        val recorded = Paths.get(state.realBinaryPath)
        val current = Files.isExecutable(recorded) && WrapBinaryVersions.newerBeside(recorded) == null
        if (current) return state.realBinaryPath
        reconcile()
        return stateStore.read().state?.realBinaryPath
    }

    /** A launch must not run while `claude` is the shim and the record of the real binary is missing or unusable:
     *  [realBinaryPath] would answer null and the launch would plant bare `claude`, which is the shim again. */
    override fun refusal(): String? {
        val problem = stateStore.read().problem() ?: return null
        return if (isWrapShim(commandPath, shimPath)) {
            "claude is the splice launch shim and the $problem, so a launch cannot tell which claude to run " +
                "(bare claude would run the shim again); restore the file, or point $commandPath at your real " +
                "claude install by hand"
        } else {
            null
        }
    }

    /** V4-129 review: the launch a `/launch/<[command]>` makes THROUGH the wrapped default command,
     *  or null. The shim takes its head from its own basename, so a wrapped `claude` posts
     *  `/launch/claude` — a name no head carries, which 404'd every wrapped `claude` until unwrap.
     *  Non-null exactly when [command] is `claude` AND the wrap state is present; the caller then
     *  launches the splice-owned Claude head over [WrappedLaunch.configDir], the vanilla ~/.claude. */
    public fun launchThrough(command: String): WrappedLaunch? =
        if (command == CLAUDE_COMMAND && realBinaryPath() != null) WrappedLaunch(vanillaDir) else null

    public fun status(): ClaudeHeadStatus {
        val cmd = commandPath
        val shim = shimPath
        val wrapped = isWrapShim(cmd, shim)
        // An unresolvable command reports no resolvesTo.
        val resolvesTo = PathProbe.resolved(cmd)?.toString()
        return ClaudeHeadStatus(
            mode = if (wrapped) "wrapped" else "separate",
            resolvesTo = resolvesTo,
            shimPath = shim.toString(),
            realBinaryPath = if (wrapped) realBinaryPath() else null,
        )
    }

    /** Wrap writes the state file and swaps the `claude` symlink for the shim, and nothing else: the vanilla
     *  ~/.claude.json and ~/.claude are neither copied nor rewritten (V4-445). */
    public fun wrap(): WrapResult = synchronized(WRAP_LOCK) {
        val cmd = commandPath
        val shim = shimPath
        when (val preflight = wrapPreflight(cmd, shim)) {
            is WrapPreflight.Refused -> WrapResult.Refused(preflight.reason)
            is WrapPreflight.Ready -> performWrap(cmd, shim, preflight)
        }
    }

    /** Puts `claude` back on the real binary the updater now points it at, or the newest one when the
     *  recorded one is gone. */
    public fun unwrap(): UnwrapResult = synchronized(WRAP_LOCK) {
        val cmd = commandPath
        val shim = shimPath
        val stored = stateStore.read()
        val state = stored.state
        when {
            !isWrapShim(cmd, shim) -> UnwrapResult.Refused("claude is not currently wrapped")
            state == null -> UnwrapResult.Refused(
                "the ${stored.problem()}, so splice cannot recover the previous '$cmd' target or " +
                    "the backed-up config; point $cmd at your real claude install by hand",
            )
            else -> performUnwrap(cmd, state)
        }
    }

    /** Keeps `claude` wrapped across Claude Code's own updates (V4-445). The updater re-points
     *  `~/.local/bin/claude` at the new version and deletes old ones, which replaces the shim and pins
     *  [WrapState.realBinaryPath] to a file that is gone; or it downloads a release beside the running one
     *  and leaves `claude` alone, and then the record moves to it. When the state says wrapped and `claude` is not the
     *  shim, this records what `claude` points at now and puts the shim back, the state first (the crash
     *  ordering in the file header). Idempotent, and quiet when nothing changed, so a directory watch and a
     *  timer can both call it. It never invents a target: a `claude` that is missing, dangling or not a
     *  symlink is [ReconcileResult.Waiting], and left exactly as found. */
    public fun reconcile(): ReconcileResult = synchronized(WRAP_LOCK) {
        when (val stored = stateStore.read()) {
            is StoredWrap.Absent -> ReconcileResult.NotWrapped
            is StoredWrap.Unreadable -> ReconcileResult.Waiting("the ${stored.problem()}, so it is left as found")
            is StoredWrap.Present -> reconcileFrom(stored.state)
        }
    }

    private fun reconcileFrom(state: WrapState): ReconcileResult {
        val cmd = commandPath
        val shim = shimPath
        return when {
            !Files.exists(shim, NOFOLLOW_LINKS) -> ReconcileResult.Waiting("launch shim not found at $shim")
            isWrapShim(cmd, shim) -> {
                stateStore.recordLauncherOwner(home, shim, installPaths.launcherProfile)
                refreshBinary(state)
            }
            !Files.exists(cmd, NOFOLLOW_LINKS) -> ReconcileResult.Waiting("$cmd is missing")
            !cmd.isSymbolicLink() -> ReconcileResult.Waiting("$cmd is not a symlink, so it is left alone")
            else -> rewrap(cmd, shim, state)
        }
    }

    private fun wrapPreflight(cmd: Path, shim: Path): WrapPreflight = when {
        !Files.exists(shim, NOFOLLOW_LINKS) ->
            WrapPreflight.Refused("launch shim not found at $shim (run: splice install)")
        isWrapShim(cmd, shim) ->
            WrapPreflight.Refused("claude is already wrapped ($cmd -> $shim)")
        !Files.exists(cmd, NOFOLLOW_LINKS) ->
            WrapPreflight.Refused(
                "no existing '$CLAUDE_COMMAND' command found at $cmd; nothing to preserve, refusing to wrap blind",
            )
        !cmd.isSymbolicLink() ->
            WrapPreflight.Refused("$cmd exists and is not a symlink; refusing to overwrite a real file")
        else -> readyFromSymlink(cmd)
    }

    private fun readyFromSymlink(cmd: Path): WrapPreflight {
        val shadowedTarget = Files.readSymbolicLink(cmd).toString()
        // The null becomes the dangling-link Refused below.
        val realBinaryPath = PathProbe.resolved(cmd)?.toString()
        return if (realBinaryPath != null) {
            WrapPreflight.Ready(shadowedTarget, realBinaryPath)
        } else {
            WrapPreflight.Refused(
                "$cmd -> $shadowedTarget does not resolve to a real file; refusing to wrap a dangling link",
            )
        }
    }

    private fun performWrap(cmd: Path, shim: Path, ready: WrapPreflight.Ready): WrapResult {
        // State BEFORE the symlink swap — the safe crash ordering (file header).
        stateStore.write(
            WrapState(
                realBinaryPath = ready.realBinaryPath,
                shadowedSymlinkTarget = ready.shadowedTarget,
                shimPath = shim.toString(),
                settingsBackupPath = "",
                claudeJsonBackupPath = "",
                wrappedAtEpochMillis = now(),
            ),
        )
        stateStore.recordLauncherOwner(home, shim, installPaths.launcherProfile)
        atomicSymlink(cmd, shim)
        return WrapResult.Ok(status())
    }

    private fun performUnwrap(cmd: Path, state: WrapState): UnwrapResult {
        val target = WrapBinaryVersions.liveTarget(cmd, state)
            ?: return UnwrapResult.Refused(
                "the claude that was wrapped (${state.shadowedSymlinkTarget}) is gone and no other version sits " +
                    "beside it; point $cmd at your real claude install by hand",
            )
        atomicSymlink(cmd, target)
        // A wrap made before V4-445 copied the operator's settings.json and .claude.json aside and rewrote
        // both; its backups go back. A wrap now records none.
        if (state.settingsBackupPath.isNotBlank()) {
            WrapBackups.restore(vanillaDir.resolve(Keys.SETTINGS), Paths.get(state.settingsBackupPath))
        }
        if (state.claudeJsonBackupPath.isNotBlank()) {
            WrapBackups.restore(vanillaDir.resolve(Keys.CLAUDE_JSON), Paths.get(state.claudeJsonBackupPath))
        }
        stateStore.clear()
        return UnwrapResult.Ok(status())
    }

    /** `claude` is the shim: move the record onto the newest version installed, whether the recorded one was
     *  deleted or a newer one now sits beside it. */
    private fun refreshBinary(state: WrapState): ReconcileResult {
        val recorded = Paths.get(state.realBinaryPath)
        val live = if (Files.isExecutable(recorded)) {
            WrapBinaryVersions.newerBeside(recorded) ?: return ReconcileResult.Intact
        } else {
            WrapBinaryVersions.newestBeside(recorded)
                ?: return ReconcileResult.Waiting("the recorded claude $recorded is gone and none sits beside it")
        }
        stateStore.write(state.copy(realBinaryPath = live.toString(), shadowedSymlinkTarget = live.toString()))
        return ReconcileResult.Rewrapped(live.toString())
    }

    /** `claude` is no longer the shim: the updater re-pointed it. Record its target as the real binary, put
     *  the shim back. */
    private fun rewrap(cmd: Path, shim: Path, state: WrapState): ReconcileResult {
        val target = Files.readSymbolicLink(cmd).toString()
        // A target that does not resolve is the Waiting below, not a failure to report.
        val real = PathProbe.resolved(cmd)
            ?: return ReconcileResult.Waiting("$cmd -> $target does not resolve yet")
        stateStore.write(
            state.copy(
                realBinaryPath = real.toString(),
                shadowedSymlinkTarget = target,
                shimPath = shim.toString(),
                wrappedAtEpochMillis = now(),
            ),
        )
        stateStore.recordLauncherOwner(home, shim, installPaths.launcherProfile)
        atomicSymlink(cmd, shim)
        return ReconcileResult.Rewrapped(real.toString())
    }

    /** Is [cmd] currently a link to [shim]? Compared by real path so a relative or differently
     *  spelled link to the same file still reads as wrapped. Absence or an unreadable entry reads as
     *  NOT wrapped — the honest default when the fact cannot be established (only proven state is
     *  asserted, matching every other absence read in this package). */
    private fun isWrapShim(cmd: Path, shim: Path): Boolean {
        // Unresolvable reads as NOT wrapped by contract (KDoc above).
        val cmdReal = PathProbe.resolved(cmd) ?: return false
        val shimReal = PathProbe.resolved(shim) ?: return false
        return cmdReal == shimReal
    }

    /** Stage + ATOMIC_MOVE (the same shape as ClaudeConfigMaterializer.replaceWithSymlink, not
     *  reusable from here — it is a private member of that class): a failure at any point before the
     *  move leaves [link] untouched, and the move itself is one atomic step with no missing-entry
     *  window either direction. */
    private fun atomicSymlink(link: Path, target: Path) {
        val staged = link.resolveSibling(".${link.fileName}.splice-wrap-${now()}")
        symlink(staged, target)
        try {
            Files.move(staged, link, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Cancellables.discard(
                Cancellables.runCatchingCleanup { Files.deleteIfExists(staged) },
                "staged-link cleanup: the move outcome must stand",
            )
        }
    }
}
