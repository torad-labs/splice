// NEW (Nov 13, 2026): `splice usage --json`, every command's logins with their plan windows as one JSON document on
// one line, for a program to read when it needs them. A program beside splice read two of Claude Code's own files
// for this, which stopped on Nov 10 when sessions began posting their status line to the daemon; this is the read
// splice offers in their place. Only the daemon holds the readings, so the verb asks it once for the accounts reply
// the console draws from, and keeps what a reader needs of each login. Within one `version`, fields are only added.
package splice.app.cli

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.cli.auth.CliSignIn
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonWire
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.MgmtKeyRead
import splice.topology.TopologyLoader

/** `splice usage --json`: [output] takes the one document, [errors] the one line said when there is none. */
internal class UsageJson(private val output: TerminalOutput, private val errors: TerminalOutput) {

    /** Asks the running daemon and prints its answer. False when it gave none, which one line on [errors] says, and
     *  then [output] gets nothing. */
    fun print(envReader: EnvReader): Boolean {
        val key = (AdminSupport.readMgmtKey(envReader) as? MgmtKeyRead.Present)?.key
        val url = "http://127.0.0.1:${AdminSupport.controlPort(envReader)}/api/accounts"
        val reply = key?.let { ControlPlaneClient.send(url, "GET", it) }
            ?.takeIf { it.status in ControlPlaneClient.OK_RANGE }
        val document = reply?.let { document(it.body, typedCommands(envReader)) }
        if (document == null) {
            errors.line("splice: the daemon gave no usage to print; `splice status` says whether it is running")
            return false
        }
        output.line(JsonWire.string(document))
        return true
    }

    /** The command a person types for each head, in the order `splice status` lists them. */
    private fun typedCommands(envReader: EnvReader): Map<String, String> {
        val signIn = CliSignIn()
        return TopologyLoader.loadOrMaterialize(TopologyLoader.configPath()).heads.mapValues { (key, head) ->
            signIn.typedCommand(key, head.claude.command ?: key, envReader)
        }
    }

    /** The document of [accountsReply], the daemon's `GET /api/accounts`, or null when it is not that reply.
     *  [commands] names each head's command; a head it does not name is listed after them under its own key. A head
     *  with no login is left out. */
    internal fun document(accountsReply: String, commands: Map<String, String>): JsonObject? {
        val reply = try {
            Json.parseToJsonElement(accountsReply) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        val rows = (reply?.get("accounts") as? JsonArray)?.filterIsInstance<JsonObject>() ?: return null
        val pools = reply["head_pools"] as? JsonObject
        return buildJsonObject {
            put("version", USAGE_DOCUMENT_VERSION)
            put(
                "commands",
                buildJsonArray {
                    (commands.keys + rows.flatMap(::headsOf)).forEach { head ->
                        val logins = rows.filter { head in headsOf(it) }
                        if (logins.isNotEmpty()) add(command(head, commands[head] ?: head, logins, pools))
                    }
                },
            )
        }
    }

    private fun headsOf(row: JsonObject): List<String> = (row["heads"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { head -> head.isString }?.content }

    /** One command: `next` is the login its next request goes to, where the command has several. Which login a
     *  running session is on is that session's own fact, and is not here. */
    private fun command(head: String, typed: String, logins: List<JsonObject>, pools: JsonObject?): JsonObject {
        val pool = (pools?.get(head) as? JsonObject)?.get("account_pool") as? JsonObject
        return buildJsonObject {
            put("head", head)
            put("command", typed)
            put("next", field(pool, "next_target_label"))
            put("accounts", buildJsonArray { logins.forEach { add(account(it, head)) } })
        }
    }

    /** One login of [head]: `label` is the name the command's pool knows it by, which is what `next` holds (null for
     *  a command with one login), `name` the one the console shows. `held` lists the models it cannot serve yet, and
     *  until when. */
    private fun account(row: JsonObject, head: String): JsonObject = buildJsonObject {
        put("label", label(row, head))
        put("name", field(row, "display_name"))
        put("kind", field(row, "kind"))
        put("plan", field(row, "plan"))
        put("five_hour", window(row, "five_hour"))
        put("seven_day", window(row, "seven_day"))
        put("models", buildJsonArray { objects(row, "seven_day_models").forEach { add(modelWeek(it)) } })
        put("held", buildJsonArray { objects(row, "models_held").forEach { add(hold(it)) } })
        put("observed_at_epoch_seconds", field(row, "observed_at_epoch_seconds"))
    }

    /** The name [head]'s pool knows this login by: the head's own label for a login several commands share, then
     *  the selector of the login Claude Code itself signed in (`native:<place>`), then the row's label. Until Nov 13,
     *  2026 the row's label stood alone, and `next` named a login no account of the command carried. */
    private fun label(row: JsonObject, head: String): JsonElement {
        val own = (row["account_labels"] as? JsonObject)?.get(head)
        return listOf(own, row["selector_key"], row["label"]).firstOrNull { it != null && it !is JsonNull } ?: JsonNull
    }

    /** One plan window of [row], or null where its provider gave no reading of it. `current` is false once the
     *  window's reset has passed and no newer reading has come. */
    private fun window(row: JsonObject, name: String): JsonElement {
        val used = field(row, "${name}_used_percent")
        if (used is JsonNull) return JsonNull
        return buildJsonObject {
            put(USAGE_USED, used)
            put(USAGE_RESETS, field(row, "${name}_reset_epoch_seconds"))
            put("current", field(row, "${name}_current"))
        }
    }

    /** One model's own week on a login, for a plan that counts a model apart from the whole week. */
    private fun modelWeek(week: JsonObject): JsonObject = buildJsonObject {
        put(USAGE_MODEL, field(week, USAGE_MODEL))
        put(USAGE_USED, field(week, USAGE_USED))
        put(USAGE_RESETS, field(week, "resets_at"))
    }

    private fun hold(held: JsonObject): JsonObject = buildJsonObject {
        put(USAGE_MODEL, field(held, USAGE_MODEL))
        put("until_epoch_seconds", field(held, "until_epoch_seconds"))
    }

    private fun objects(row: JsonObject, name: String): List<JsonObject> =
        (row[name] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun field(of: JsonObject?, name: String): JsonElement = of?.get(name) ?: JsonNull
}

// why: the first shape of the document. A reader checks it, and it moves only when a field changes its meaning or
// goes; a field added leaves it as it is.
private const val USAGE_DOCUMENT_VERSION = 1

private const val USAGE_USED = "used_percent"
private const val USAGE_RESETS = "resets_at_epoch_seconds"
private const val USAGE_MODEL = "model"
