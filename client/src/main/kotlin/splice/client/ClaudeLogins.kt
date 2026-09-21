// NEW: V4-129 (FEATURES.md 4.5 "Claude logins"). Several of Claude Code's OWN `.credentials.json`
// files, stored by splice, ONE materialized into the splice-owned Claude head's (claude-splice)
// config dir at session launch. Splice never reads or interprets the bytes — the file's shape is
// Claude Code's own login state, not splice's — this is a labeled copy store plus a selection
// pointer. Materialization is LAUNCH-TIME ONLY (called from LaunchService), never mid-session: "one
// login per head at a time" holds because there is exactly one SELECTED label at any moment, never
// because anything watches a running session. Splice never calls Anthropic with the bytes it stores
// here — traffic still flows through Claude Code's own login, entirely client-side.
package splice.client

import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

private const val LOGINS_DIR = "claude-logins"
private const val SELECTED_FILE = "selected"
private const val CREDENTIALS_FILE = ".credentials.json"
private val LABEL_SHAPE = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")

public sealed class ClaudeLoginResult {
    public data object Ok : ClaudeLoginResult()
    public data class Refused(val reason: String) : ClaudeLoginResult()
}

public class ClaudeLogins(
    private val storeDir: Path = StatePaths().stateDir.resolve(LOGINS_DIR),
) {
    /** Every stored label, sorted. An absent store dir (never used) reads as no logins, not a
     *  failure — the honest default for a feature nobody has touched yet. */
    public fun labels(): List<String> = Cancellables.runCatchingCancellable {
        Files.list(storeDir).use { entries ->
            entries
                .map { it.fileName.toString() }
                .filter { it.endsWith(CREDENTIALS_FILE) }
                .map { it.removeSuffix(CREDENTIALS_FILE) }
                .sorted()
                .toList()
        }
    }.getOrElse { emptyList() }

    /** The currently selected label, or null when nothing is selected OR the marker names a label
     *  that was since removed (a stale marker is not a selection). */
    public fun selected(): String? {
        val marker = Cancellables.runCatchingCancellable { Files.readString(selectedFile()).trim() }.getOrNull()
        return marker?.takeIf { it.isNotEmpty() && it in labels() }
    }

    public fun select(label: String): ClaudeLoginResult {
        if (label !in labels()) return ClaudeLoginResult.Refused("no stored Claude login named '$label'")
        SecureFile.writeAtomic0600(selectedFile(), label)
        return ClaudeLoginResult.Ok
    }

    /** Store [sourceConfigDir]'s CURRENT `.credentials.json` (whatever Claude Code's own `/login`
     *  just wrote there) under [label], verbatim — no parsing, no reshaping. */
    public fun store(label: String, sourceConfigDir: Path): ClaudeLoginResult {
        if (!LABEL_SHAPE.matches(label)) {
            return ClaudeLoginResult.Refused("'$label' is not a valid login label (letters, digits, - or _ only)")
        }
        val source = sourceConfigDir.resolve(CREDENTIALS_FILE)
        val content = Cancellables.runCatchingCancellable { Files.readString(source) }.getOrNull()
        return if (content == null) {
            ClaudeLoginResult.Refused("no readable $source to store — sign in on this head first")
        } else {
            SecureFile.writeAtomic0600(storeDir.resolve("$label$CREDENTIALS_FILE"), content)
            ClaudeLoginResult.Ok
        }
    }

    public fun remove(label: String): ClaudeLoginResult {
        if (label !in labels()) return ClaudeLoginResult.Refused("no stored Claude login named '$label'")
        Files.deleteIfExists(storeDir.resolve("$label$CREDENTIALS_FILE"))
        if (selected() == label) {
            Cancellables.discard(
                Cancellables.runCatchingCancellable { Files.deleteIfExists(selectedFile()) },
                "clearing the selection of a removed login is best-effort — a stale marker already reads as absent",
            )
        }
        return ClaudeLoginResult.Ok
    }

    /** Launch-time materialization (called from LaunchService): copies the SELECTED login's stored
     *  bytes into [targetConfigDir]/.credentials.json. No selection, or the selected file vanished
     *  underneath it, is a silent no-op: a head with this feature never configured behaves exactly
     *  as it did before this row landed. Returns whether a login was materialized. */
    public fun materializeSelected(targetConfigDir: Path): Boolean {
        val label = selected() ?: return false
        val content = Cancellables.runCatchingCancellable {
            Files.readString(storeDir.resolve("$label$CREDENTIALS_FILE"))
        }.getOrNull() ?: return false
        SecureFile.writeAtomic0600(targetConfigDir.resolve(CREDENTIALS_FILE), content)
        return true
    }

    private fun selectedFile(): Path = storeDir.resolve(SELECTED_FILE)
}
