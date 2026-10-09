// NEW: V4-129 (split out of WrappedHead.kt, 2026-10-09) — the wrap state file and the launcher-owner record beside the
// shim: the one fact every launch reads, and what a launch must never mistake it for. A file that is there and cannot be
// used is [StoredWrap.Unreadable], not absence; WrappedHead decides what each answer means for a launch.
package splice.client.wrap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.core.util.FileTightening
import splice.core.util.JsonScalars
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths

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
