// NEW: V4-129 — the default-command shim (FEATURES.md 4.12 "Wrap"). The operator's plain `claude`
// on PATH becomes a splice launcher over the vanilla config dir (~/.claude) instead of an isolated
// tree. Two hazards this file exists to close:
//   - SELF-EXEC: app/src/main/dist/bin/splice-launch execs its recipe's argv[0] by resolving it through PATH, and
//     LaunchService plants the bare string "claude" there. The moment `claude` on PATH IS the shim,
//     every head's launch (not only the wrapped one) would resolve argv[0] back to the shim that is
//     currently running, recursing forever. WrapStateStore is the one fact LaunchService reads on
//     every launch (WrapStateRead) to plant the REAL absolute binary path instead — written BEFORE
//     the symlink swap below, so a crash between the two leaves LaunchService already answering the
//     absolute path while `claude` on PATH is still, in fact, untouched (harmless: same target,
//     spelled absolutely) rather than the reverse ordering, which would leave every head resolving
//     a shim with nothing telling them not to.
//   - DR-102: ClaudeConfigMaterializer.materialize() refuses to write into ~/.claude by design (the
//     isolation guard). Wrap's whole point is to write there, so it goes through the deliberately
//     narrow materializeWrap() entry point instead — never a bypass of the guard, a different door.
// "no pool, no isolation, no un-link on a wrapped head" (the row title): this class does not touch
// Topology, ManagedHead or the account pool — it is a self-contained shim-and-two-files mechanism;
// the MaterializeSpec it writes is handed in by the caller (ClaudeHeadRoutes), which is the one that
// knows about the claude-splice head's own catalog.
// DTOs, read seams and outcome types live in WrappedHeadTypes.kt (concentration, 2026-09-20). Same
// package, same FQCNs.
package splice.client.wrap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.Keys
import splice.client.MaterializeSpec
import splice.client.SymlinkOp
import splice.core.config.InstallPaths
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.isSymbolicLink

private const val CLAUDE_COMMAND = "claude"
private const val SHIM_NAME = "splice-launch"
private const val WRAP_STATE_FILE = "claude-head-wrap.json"

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

    // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-24: unwrap() refuses on null, naming missing or unreadable state; status omits the path
    public fun read(): WrapState? = Cancellables.runCatchingCancellable {
        val obj = json.parseToJsonElement(Files.readString(file)) as JsonObject
        val realBinaryPath = JsonScalars.str(obj, "real_binary_path") ?: return@runCatchingCancellable null
        WrapState(
            realBinaryPath = realBinaryPath,
            shadowedSymlinkTarget = JsonScalars.strOrEmpty(obj["shadowed_symlink_target"]),
            shimPath = JsonScalars.strOrEmpty(obj["shim_path"]),
            settingsBackupPath = JsonScalars.strOrEmpty(obj["settings_backup_path"]),
            claudeJsonBackupPath = JsonScalars.strOrEmpty(obj["claude_json_backup_path"]),
            wrappedAtEpochMillis = JsonScalars.long(obj, "wrapped_at_epoch_millis") ?: 0L,
        )
    }.getOrNull()

    public fun write(state: WrapState) {
        val body = buildJsonObject {
            put("real_binary_path", state.realBinaryPath)
            put("shadowed_symlink_target", state.shadowedSymlinkTarget)
            put("shim_path", state.shimPath)
            put("settings_backup_path", state.settingsBackupPath)
            put("claude_json_backup_path", state.claudeJsonBackupPath)
            put("wrapped_at_epoch_millis", state.wrappedAtEpochMillis)
        }
        SecureFile.writeAtomic0600(file, json.encodeToString(JsonObject.serializer(), body) + "\n")
    }

    /** Best-effort: an unreadable leftover already answers [read] as absent (proven-absence law), so
     *  a failed delete here cannot make unwrap report the wrong mode — only leaves a stale file a
     *  later wrap's write() will overwrite anyway. */
    public fun clear() {
        Cancellables.discard(
            Cancellables.runCatchingCancellable { Files.deleteIfExists(file) },
            "wrap-state clear is best-effort — an absent-or-unreadable file already reads as unwrapped",
        )
    }
}

// WrapState, WrapStateRead, ClaudeHeadStatus, WrapResult and UnwrapResult live in
// WrappedHeadTypes.kt (concentration split, 2026-09-20) — same package, same FQCNs.

/** The pre-flight read [WrappedHead.wrap] needs before it writes anything — split out so neither
 *  function's return count trips the wall (Kotlin style law: ReturnCount <= 3). */
private sealed class WrapPreflight {
    data class Ready(val shadowedTarget: String, val realBinaryPath: String) : WrapPreflight()
    data class Refused(val reason: String) : WrapPreflight()
}

/** V4-129: wrap/unwrap orchestration — see file header for the two hazards this closes. */
public class WrappedHead(
    private val home: Path,
    private val installPaths: InstallPaths = InstallPaths(),
    private val stateStore: WrapStateStore = WrapStateStore(),
    private val materializer: ClaudeConfigMaterializer = ClaudeConfigMaterializer(home),
    private val symlink: SymlinkOp = SymlinkOp { link, target -> Files.createSymbolicLink(link, target) },
    private val now: WallClock = WallClock(System::currentTimeMillis),
) {
    private val commandPath: Path get() = installPaths.binDir.resolve(CLAUDE_COMMAND)
    private val shimPath: Path get() = installPaths.shareDir.resolve(SHIM_NAME)
    private val vanillaDir: Path get() = home.resolve(Keys.CLAUDE)

    public fun status(): ClaudeHeadStatus {
        val cmd = commandPath
        val shim = shimPath
        val wrapped = isWrapShim(cmd, shim)
        return ClaudeHeadStatus(
            mode = if (wrapped) "wrapped" else "separate",
            resolvesTo = resolveCommand(cmd),
            shimPath = shim.toString(),
            realBinaryPath = if (wrapped) stateStore.read()?.realBinaryPath else null,
        )
    }

    /** [spec] arrives from the caller with its own configDir/policy — both are OVERRIDDEN here: the
     *  vanilla dir is the only legal target for wrap, and the policy is fixed so settings.json's
     *  "global" layer (the very file about to be overwritten, once configDir == vanillaDir) is
     *  always carried forward, regardless of the source head's own share/isolate configuration. */
    public fun wrap(spec: MaterializeSpec): WrapResult {
        val cmd = commandPath
        val shim = shimPath
        return when (val preflight = wrapPreflight(cmd, shim)) {
            is WrapPreflight.Refused -> WrapResult.Refused(preflight.reason)
            is WrapPreflight.Ready -> performWrap(spec, cmd, shim, preflight)
        }
    }

    public fun unwrap(): UnwrapResult {
        val cmd = commandPath
        val shim = shimPath
        if (!isWrapShim(cmd, shim)) return UnwrapResult.Refused("claude is not currently wrapped")
        val state = stateStore.read()
        return if (state == null) {
            UnwrapResult.Refused(
                "wrap state is missing or unreadable — cannot recover the previous '$cmd' target or " +
                    "the backed-up config; point $cmd at your real claude install by hand",
            )
        } else {
            performUnwrap(cmd, state)
        }
    }

    private fun wrapPreflight(cmd: Path, shim: Path): WrapPreflight = when {
        !Files.exists(shim, NOFOLLOW_LINKS) ->
            WrapPreflight.Refused("launch shim not found at $shim (run: splice install)")
        isWrapShim(cmd, shim) ->
            WrapPreflight.Refused("claude is already wrapped ($cmd -> $shim)")
        !Files.exists(cmd, NOFOLLOW_LINKS) ->
            WrapPreflight.Refused(
                "no existing '$CLAUDE_COMMAND' command found at $cmd — nothing to preserve, refusing to wrap blind",
            )
        !cmd.isSymbolicLink() ->
            WrapPreflight.Refused("$cmd exists and is not a symlink — refusing to overwrite a real file")
        else -> readyFromSymlink(cmd)
    }

    private fun readyFromSymlink(cmd: Path): WrapPreflight {
        val shadowedTarget = Files.readSymbolicLink(cmd).toString()
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-24: the null becomes the dangling-link Refused below
        val realBinaryPath = Cancellables.runCatchingCancellable { cmd.toRealPath().toString() }.getOrNull()
        return if (realBinaryPath != null) {
            WrapPreflight.Ready(shadowedTarget, realBinaryPath)
        } else {
            WrapPreflight.Refused(
                "$cmd -> $shadowedTarget does not resolve to a real file — refusing to wrap a dangling link",
            )
        }
    }

    private fun performWrap(spec: MaterializeSpec, cmd: Path, shim: Path, ready: WrapPreflight.Ready): WrapResult {
        val wrapSpec = spec.copy(
            configDir = vanillaDir,
            policy = ClaudePolicy(share = setOf(Keys.SETTINGS), isolate = emptySet()),
        )
        val settingsBackup = backupPath(vanillaDir.resolve(Keys.SETTINGS))
        val claudeJsonBackup = backupPath(vanillaDir.resolve(Keys.CLAUDE_JSON))
        Files.createDirectories(vanillaDir)
        backup(vanillaDir.resolve(Keys.SETTINGS), settingsBackup)
        backup(vanillaDir.resolve(Keys.CLAUDE_JSON), claudeJsonBackup)
        materializer.materializeWrap(wrapSpec)
        // State BEFORE the symlink swap — the safe crash ordering (file header).
        stateStore.write(
            WrapState(
                realBinaryPath = ready.realBinaryPath,
                shadowedSymlinkTarget = ready.shadowedTarget,
                shimPath = shim.toString(),
                settingsBackupPath = settingsBackup.toString(),
                claudeJsonBackupPath = claudeJsonBackup.toString(),
                wrappedAtEpochMillis = now(),
            ),
        )
        atomicSymlink(cmd, shim)
        return WrapResult.Ok(status(), settingsBackup.toString(), claudeJsonBackup.toString())
    }

    private fun performUnwrap(cmd: Path, state: WrapState): UnwrapResult {
        atomicSymlink(cmd, Paths.get(state.shadowedSymlinkTarget))
        restore(vanillaDir.resolve(Keys.SETTINGS), Paths.get(state.settingsBackupPath))
        restore(vanillaDir.resolve(Keys.CLAUDE_JSON), Paths.get(state.claudeJsonBackupPath))
        stateStore.clear()
        return UnwrapResult.Ok(status())
    }

    /** Is [cmd] currently a link to [shim]? Compared by real path so a relative or differently
     *  spelled link to the same file still reads as wrapped. Absence or an unreadable entry reads as
     *  NOT wrapped — the honest default when the fact cannot be established (only proven state is
     *  asserted, matching every other absence read in this package). */
    private fun isWrapShim(cmd: Path, shim: Path): Boolean {
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-24: unresolvable reads as NOT wrapped by contract (KDoc above)
        val cmdReal = Cancellables.runCatchingCancellable { cmd.toRealPath() }.getOrNull() ?: return false
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-24: unresolvable reads as NOT wrapped by contract (KDoc above)
        val shimReal = Cancellables.runCatchingCancellable { shim.toRealPath() }.getOrNull() ?: return false
        return cmdReal == shimReal
    }

    private fun resolveCommand(cmd: Path): String? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-24: an unresolvable command reports no resolvesTo in the status
        Cancellables.runCatchingCancellable { cmd.toRealPath().toString() }.getOrNull()

    private fun backupPath(original: Path): Path =
        original.resolveSibling("${original.fileName}.splice-wrap-backup-${now()}")

    /** No-op when [original] does not exist — its own absence IS what [restore] must reproduce, and
     *  an absent backup path is how it tells the two states apart. */
    private fun backup(original: Path, backupTo: Path) {
        if (!Files.exists(original, NOFOLLOW_LINKS)) return
        Files.copy(original, backupTo, COPY_ATTRIBUTES)
    }

    private fun restore(target: Path, backupFrom: Path) {
        if (!Files.exists(backupFrom, NOFOLLOW_LINKS)) {
            Cancellables.discard(
                Cancellables.runCatchingCancellable { Files.deleteIfExists(target) },
                "restoring to absence — the pre-wrap state genuinely had nothing here",
            )
            return
        }
        Files.move(backupFrom, target, REPLACE_EXISTING, ATOMIC_MOVE)
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
                "staged-link cleanup — the move outcome must stand",
            )
        }
    }
}
