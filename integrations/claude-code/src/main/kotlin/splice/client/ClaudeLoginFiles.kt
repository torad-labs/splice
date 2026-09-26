// NEW: V4-276 — the two sides ClaudeLogins.login reads and writes. The head's: Claude Code's own
// .credentials.json (bytes, never parsed) and the account its .claude.json names in oauthAccount.
// splice's: the store of labelled copies, each beside its account record, and the selection. Split
// out of ClaudeLogins.kt for the concentration law.
package splice.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

private const val SELECTED_FILE = "selected"
private const val CREDENTIALS_FILE = ".credentials.json"
private const val ACCOUNT_FILE = ".account.json"
private const val OAUTH_ACCOUNT = "oauthAccount"
private const val ACCOUNT_UUID = "accountUuid"
private const val EMAIL = "emailAddress"
private const val STALE = "stale"

/** The head's side: its live credential file, read as bytes, and the account its .claude.json names. */
internal object HeadLogin {
    private val json = Json { ignoreUnknownKeys = true }

    /** The strict read the materializer makes: absent is an empty object, anything unreadable throws. */
    private val reads = JsonStateReads(json, LogSink(DaemonLog::write))

    fun config(configDir: Path): JsonObject = reads.strict(configDir.resolve(Keys.CLAUDE_JSON))

    /** Claude Code's own credential file in the head's config dir: the live login. */
    fun credentials(configDir: Path): Path = configDir.resolve(CREDENTIALS_FILE)

    fun read(configDir: Path): Live {
        val credentials = credentials(configDir)
        if (!Files.exists(credentials, NOFOLLOW_LINKS)) return Live.Absent
        val account = account(config(configDir)[OAUTH_ACCOUNT] as? JsonObject)
            ?: return Live.Unreadable("its ${Keys.CLAUDE_JSON} names no oauthAccount.$ACCOUNT_UUID")
        return Live.Held(Files.readString(credentials), account)
    }

    fun account(fields: JsonObject?): Account? {
        val uuid = fields?.let { JsonScalars.str(it, ACCOUNT_UUID) }?.takeIf { it.isNotBlank() } ?: return null
        return Account(uuid, JsonScalars.str(fields, EMAIL))
    }

    fun fields(account: Account): JsonObject = buildJsonObject {
        put(ACCOUNT_UUID, account.uuid)
        account.email?.let { put(EMAIL, it) }
    }

    /** Only the two identity fields: Claude Code 2.1.283 re-fetches the whole profile from the token
     *  whenever billingType, accountCreatedAt, subscriptionCreatedAt or ccOnboardingFlags is missing. */
    fun writeAccount(configDir: Path, config: JsonObject, account: Account) {
        val next = buildJsonObject {
            config.forEach { (key, value) -> if (key != OAUTH_ACCOUNT) put(key, value) }
            putJsonObject(OAUTH_ACCOUNT) { fields(account).forEach { (key, value) -> put(key, value) } }
        }
        val text = json.encodeToString(JsonObject.serializer(), next) + "\n"
        SecureFile.writeAtomic0600(configDir.resolve(Keys.CLAUDE_JSON), text)
    }
}

/** splice's side: `<label>.credentials.json` (the bytes), `<label>.account.json` (the V4-276 record)
 *  and `selected`, all owner-only. A label with no readable record is a legacy copy. */
internal class LoginStore(private val dir: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    fun labels(): List<String> = Cancellables.runCatchingCancellable {
        Files.list(dir).use { entries ->
            entries
                .map { it.fileName.toString() }
                .filter { it.endsWith(CREDENTIALS_FILE) }
                .map { it.removeSuffix(CREDENTIALS_FILE) }
                .sorted()
                .toList()
        }
    }.getOrElse { emptyList() }

    fun selected(): String? {
        val file = dir.resolve(SELECTED_FILE)
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-24: no readable marker is "nothing selected" by contract
        val marker = Cancellables.runCatchingCancellable { Files.readString(file).trim() }.getOrNull()
        return marker?.takeIf { it.isNotEmpty() && it in labels() }
    }

    fun select(label: String) = SecureFile.writeAtomic0600(dir.resolve(SELECTED_FILE), label)

    fun unselect() {
        Files.deleteIfExists(dir.resolve(SELECTED_FILE))
    }

    /** Every label with a readable record. A record that cannot be read leaves its label out, so the
     *  copy is treated as legacy and never put back (fail closed). */
    fun records(): Map<String, Record> = labels().mapNotNull { label ->
        val read = Cancellables.runCatchingCancellable {
            val fields = json.parseToJsonElement(Files.readString(record(label))).jsonObject
            HeadLogin.account(fields)?.let { Record(it, stale = JsonScalars.str(fields, STALE) == "true") }
        }
        val account = read.getOrElse {
            Cancellables.discard(read, "an unreadable record is a legacy copy, which is never put back")
            null
        }
        account?.let { label to it }
    }.toMap()

    /** The live login's bytes are its account's newest, so saving them clears a stale mark. The
     *  bytes land first and the record second: a record that fails to land leaves the copy legacy or
     *  stale, never a record naming bytes of another account. */
    fun save(label: String, bytes: String, account: Account) {
        SecureFile.writeAtomic0600(dir.resolve("$label$CREDENTIALS_FILE"), bytes)
        write(label, Record(account, stale = false))
    }

    fun credentials(label: String): String = Files.readString(dir.resolve("$label$CREDENTIALS_FILE"))

    /** The copy stays on disk and its record keeps the account, marked stale: never put back until
     *  a live login of that account is saved over it. A legacy label has no record to mark. */
    fun demote(label: String) {
        records()[label]?.let { write(label, it.copy(stale = true)) }
        unselect()
    }

    private fun write(label: String, entry: Record) {
        val fields = buildJsonObject {
            HeadLogin.fields(entry.account).forEach { (key, value) -> put(key, value) }
            if (entry.stale) put(STALE, true)
        }
        SecureFile.writeAtomic0600(record(label), json.encodeToString(JsonObject.serializer(), fields))
    }

    fun remove(label: String) {
        val wasSelected = selected() == label
        Files.deleteIfExists(dir.resolve("$label$CREDENTIALS_FILE"))
        Files.deleteIfExists(record(label))
        if (wasSelected) unselect()
    }

    private fun record(label: String): Path = dir.resolve("$label$ACCOUNT_FILE")
}
