// NEW: Oct 28, 2026 (docs/peers.md, "A Codex thread as a peer"; Marcos: "add the setup offer to write codex hooks"):
// `splice setup`'s offer to join Codex threads to splice peer messaging. A Codex thread is a peer only through hooks in
// its Codex home's hooks.json ($CODEX_HOME, else ~/.codex), which splice never wrote before: the ones on Marcos's
// machine were written by hand and sent splice's stdout to stderr, so no message could reach a thread through them.
// Asked only when someone watches, a Codex home exists and its hooks.json does not already run the hook. A yes adds
// `splice peer codex hook` to the five events it answers (PeerCodexHook.kt), takes out the hand-written splice attach
// and detach entries it replaces, and keeps every other hook. The file as it was is kept beside it first, and a
// hooks.json that is a link is written where the link leads, so the homes that share it keep sharing it. A write that
// fails is said in a line and never fails the setup.
package splice.app.cli.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.config.UserHome
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.SecureFile
import splice.launch.install.InstallLayout
import splice.terminal.WizardFrame
import java.nio.file.Files
import java.nio.file.Path

private const val CODEX_HOME_ENV = "CODEX_HOME"
private const val HOOK_VERB = " peer codex hook"
private const val HOOKS = "hooks"
private val REPLACED = listOf("splice peer codex attach", "splice peer codex detach")
private val EVENTS = listOf("SessionStart", "UserPromptSubmit", "PostToolUse", "Stop", "SessionEnd")

// why: a hand-over reads a few files, and an attach waits at most 10 s for the peer's helper (spec 3.2).
private const val HOOK_TIMEOUT_S = 30

internal class SetupCodexHooks(private val frame: WizardFrame, env: EnvReader) {
    private val json = Json { prettyPrint = true }

    /** $CODEX_HOME, else ~/.codex. While a home is lent to the process (UserHome.within), $CODEX_HOME is the
     *  process's own and is not read, so a setup run under a lent home never reaches the Codex home of whoever runs
     *  it. */
    private val home: Path = env(CODEX_HOME_ENV)?.takeIf { it.isNotBlank() && !lent(env) }?.let(Path::of)
        ?: UserHome.dir(env).resolve(".codex")
    private val file: Path = home.resolve("hooks.json")
    private val command = "'${InstallLayout().localBin(env).resolve("splice")}'$HOOK_VERB"

    /** The answer, or null when nothing is asked: no one watches, there is no Codex home, or the hook is there. */
    fun ask(watched: Boolean): Boolean? =
        if (!watched || !offerable()) {
            null
        } else {
            frame.confirm("Let Codex threads send and receive splice peer messages? This adds a hook to $file.", true)
        }

    fun summary(answer: Boolean?): List<String> =
        if (answer == true) listOf("Codex threads join splice peer messaging through $file") else emptyList()

    /** Writes the hook when [answer] is yes. */
    fun save(answer: Boolean?) {
        if (answer != true) return
        val written = Cancellables.runCatchingBestEffort {
            val target = if (Files.exists(file)) file.toRealPath() else file
            val before = if (Files.exists(target)) Files.readString(target) else null
            val root = before?.let { JsonScalars.objectOrNull(json, it) } ?: JsonObject(emptyMap())
            before?.let { SecureFile.writeAtomic0600(target.resolveSibling(backupName()), it) }
            SecureFile.writeAtomic0600(target, json.encodeToString(JsonObject.serializer(), merged(root)) + "\n")
        }
        written.exceptionOrNull()?.let {
            System.err.println("splice: the Codex hooks were not written to $file (${it.message}); see docs/peers.md")
        }
    }

    /** A Codex home is there and its hooks.json does not run the hook yet. */
    private fun offerable(): Boolean = Files.isDirectory(home) && !present()

    private fun present(): Boolean =
        Files.isRegularFile(file) && Cancellables.runCatchingBestEffort { Files.readString(file) }
            .getOrNull().orEmpty().contains(HOOK_VERB)

    /** [root] with the hook on every event it answers, splice's hand-written hooks out, and everything else kept. */
    private fun merged(root: JsonObject): JsonObject {
        val events = (root[HOOKS] as? JsonObject ?: JsonObject(emptyMap()))
            .mapValues { (_, groups) -> withoutReplaced(groups) }
        val hooks = events.filterValues { it.isNotEmpty() }.toMutableMap()
        EVENTS.forEach { event -> hooks[event] = JsonArray(list(hooks[event]) + group()) }
        return JsonObject(root + (HOOKS to JsonObject(hooks)))
    }

    /** The event's groups with each hand-written splice attach and detach taken out, and a group left empty gone. */
    private fun withoutReplaced(groups: JsonElement): JsonArray = JsonArray(
        list(groups).mapNotNull { group ->
            val entry = group as? JsonObject ?: return@mapNotNull group
            val kept = list(entry[HOOKS]).filterNot { hook ->
                val line = JsonScalars.str(hook as? JsonObject, "command").orEmpty()
                REPLACED.any(line::contains)
            }
            if (kept.isEmpty()) null else JsonObject(entry + (HOOKS to JsonArray(kept)))
        },
    )

    private fun group(): JsonObject = buildJsonObject {
        putJsonArray(HOOKS) {
            add(
                buildJsonObject {
                    put("type", "command")
                    put("command", command)
                    put("timeout", HOOK_TIMEOUT_S)
                },
            )
        }
    }

    private fun lent(env: EnvReader): Boolean = UserHome.dir(env) != UserHome.processDir(env)

    private fun backupName(): String = "hooks.json.bak-splice-${System.currentTimeMillis()}"

    /** [element]'s items when it is an array, else none. */
    private fun list(element: JsonElement?): List<JsonElement> = element as? JsonArray ?: emptyList()
}
