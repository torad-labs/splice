// NEW: the two OS-touching primitives shared by every login flow (browser OAuth + device flow):
// openBrowser (best-effort, loopback-safe) and writeCredentialFile (atomic 0600 write, no
// world-readable window). Extracted verbatim from OAuthLoginFlow so DeviceLoginFlow reuses the
// exact same secure-write pattern instead of re-deriving it. Every operator-facing line goes out
// through [TerminalOutput] (LAYOUT-01); the terminal-and-install half lives in cli/auth/CliSignIn.kt.
package splice.app.auth

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Path

/** Set by the shared Gradle test task. Its presence means "you are inside the suite", and the
 *  system browser refuses rather than opening a window on the operator's desktop. A system PROPERTY
 *  rather than an env var: `System.getenv` is walled to core/config (kt-no-system-getenv), and a
 *  guard that exists only for the test JVM has no business on the layered config path anyway. */
private const val NO_SYSTEM_BROWSER = "splice.noSystemBrowser"

/** What [SystemBrowserOpener.host] reports when the authorize URL does not parse — the classification the
 *  parse failure is routed into, since the URL itself carries the PKCE challenge and never gets printed. */
private const val UNKNOWN_HOST = "unknown"

/** Opens a login URL; tests record the request without starting an operating-system process. */
public fun interface BrowserOpener {
    public fun open(url: String): Boolean
}

/** V4-132: the console's login-id/poll seam over [DeviceLoginFlow] and [OAuthLoginFlow] — what
 *  each flow reports the moment it has something externally visible to show (the device code and
 *  its verification link, or the OAuth authorize URL), so LoginSessions can answer
 *  GET /api/auth/{head}/login/{id} before the flow itself has finished. A NULL observer (every CLI
 *  call site) means "print to this terminal and open this desktop's browser", exactly as before;
 *  a non-null one means the flow is being watched off-request, so it reports through here instead.
 *  Public (not internal): it rides [DeviceLoginFlow.run]/[OAuthLoginFlow.run]'s own public
 *  signature, and explicit-API mode refuses a public function that exposes an internal type. */
public fun interface LoginObserver {
    public fun announced(detail: LoginAnnouncement)
}

/** One flow's externally-visible progress. [userCode]/[verificationUri] are the device flow's;
 *  [browserUrl] is the OAuth flow's — a flow fills only the pair it has. */
public data class LoginAnnouncement(
    val userCode: String? = null,
    val verificationUri: String? = null,
    val browserUrl: String? = null,
)

/** The operator's desktop browser. Public: `splice console` opens its page through it as well. */
public class SystemBrowserOpener(private val output: TerminalOutput) : BrowserOpener {

    /** WALL (2026-09-16). A TEST must never launch the operator's browser. SetupCommandTest
     *  constructed SetupCommand without overriding its loginHead seam, so the wizard ran a REAL
     *  grok OAuth login on every `:app:test`: it opened accounts.x.ai in the operator's Chrome,
     *  bound the loopback callback port, and then blocked in awaitCode for a code that could never
     *  arrive. For a full day that read as the DAEMON re-prompting for sign-in — the operator saw a
     *  login page appear again and again with no turn behind it — and it was the build all along.
     *  The guard is set by the shared Gradle test task, so any future test reaching this path fails
     *  loudly and names itself instead of opening a window on someone's desktop. */
    override fun open(url: String): Boolean {
        if (System.getProperty(NO_SYSTEM_BROWSER) != null) {
            error(
                "a test reached the real system browser (host=${host(url)}); inject a BrowserOpener " +
                    "fake, or override the flow's login seam (SetupCommand.loginHead)",
            )
        }
        return launch(url)
    }

    /** Host only — an authorize URL carries the PKCE challenge and state, which never belong in a
     *  failure message or a log. */
    private fun host(url: String): String =
        Cancellables.runCatchingCancellable { java.net.URI.create(url).host }
            .fold(onSuccess = { it ?: UNKNOWN_HOST }, onFailure = { UNKNOWN_HOST })

    private fun launch(url: String): Boolean = Cancellables.runCatchingCancellable {
        val os = System.getProperty("os.name").lowercase()
        val cmd = when {
            os.contains("mac") -> listOf("open", url)
            os.contains("nux") || os.contains("nix") -> listOf("xdg-open", url)
            else -> return false
        }
        ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        true
    }.onFailure { output.line("splice: could not open a browser (${SafeFailureText.render(it)})") }
        .getOrDefault(false)
}

/** The shared login I/O primitives, held as a collaborator by each flow (Kotlin style law,
 *  2026-08-15): a helper used by several types is a small named class they construct, not a pair
 *  of free functions. */
internal class LoginIo(
    private val output: TerminalOutput,
    private val browser: BrowserOpener = SystemBrowserOpener(output),
) {

    private val loginJson = Json { ignoreUnknownKeys = true }

    /** Best-effort open of a URL in the operator's default browser; false when unsupported/failed. */
    internal fun openBrowser(url: String): Boolean = browser.open(url)

    // Write credentials atomically at 0600 — routes to the shared primitive. This file held the
    // canonical copy SecureFile was lifted from; delegating keeps a single source of truth.
    internal fun writeCredentialFile(path: Path, content: String) {
        SecureFile.writeAtomic0600(path, content)
    }

    /** DR-172: an HTTP 200 is not a sign-in, and this is the boundary that decides the message.
     *
     *  Both flows treated `isSuccess` as the whole test and wrote whatever the body produced. A
     *  token endpoint answering 200 with `{}` therefore had an EMPTY access token persisted at
     *  0600 under "signed in — credentials written to …", and the operator walked away believing
     *  they were authenticated while every later turn failed on a credential that was never
     *  issued. Kimi already refused exactly this input — kimiAuthJsonFromTokenResponse errors on a
     *  missing access_token, with a test pinning it — so the correct behaviour was established
     *  in-repo and two providers diverged from it.
     *
     *  The check lives here rather than in each provider's toAuthJson because BOTH login flows
     *  print the same sentence from the same collaborator; per-provider guards would have to be
     *  re-derived for the next provider and for the device flow, which had the identical shape.
     *
     *  Fail-closed on an unparseable body too: a credential file whose token we cannot read is not
     *  one to call a successful login. Nothing is written on refusal — the previous credential, if
     *  any, is left intact rather than replaced by a worthless one. */
    internal fun persistIfSignedIn(
        path: Path,
        authJson: String,
        account: OAuthLoginAccount? = null,
    ): Boolean {
        val parsed = Cancellables.runCatchingCancellable {
            loginJson.parseToJsonElement(authJson) as? JsonObject
        }.onFailure {
            output.line("splice: token endpoint body did not parse (${SafeFailureText.render(it)})")
        }.getOrNull()
        val token = parsed?.let(::accessTokenOf)
        if (token.isNullOrBlank()) {
            output.line("splice: token endpoint returned no access token — NOT signed in, nothing written")
            return false
        }
        val target = Cancellables.runCatchingCancellable {
            if (account == null || account.primary) {
                writeCredentialFile(path, authJson)
                path
            } else {
                persistLabeled(path, account, parsed)
            }
        }.getOrElse { failure ->
            output.line("splice: credential persistence error: ${SafeFailureText.render(failure)}")
            null
        } ?: return false
        output.line("splice: signed in — credentials written to $target")
        return true
    }

    private fun persistLabeled(path: Path, account: OAuthLoginAccount, parsed: JsonObject): Path? {
        val label = account.resolvedLabel(parsed)
        if (label.isNullOrBlank()) {
            output.line("splice: token endpoint returned no stable account id — NOT signed in, nothing written")
            return null
        }
        val files = OAuthAccountFiles(loginJson)
        val target = if (!account.tokenDerivedLabel) {
            files.writeLabeled(account.kind, path, label, parsed)
        } else {
            val written = files.writeTokenDerived(account.kind, path, label, parsed, account.identity)
            written.retainedQuota?.let { quota ->
                output.line(
                    "splice: retained quota in ${quota.fileName} — saved credentials as ${written.file.fileName}",
                )
            }
            written.file
        }
        account.recordPersistedLabel(target.fileName.toString().removeSuffix(".json"))
        account.releaseReservation()
        return target
    }

    /** DR-172 gap (2026-09-01): the codex and grok login specs hand this the ON-DISK shape their
     *  providers read back — the token nested under "tokens" (CodexAuthJson / GrokAuthJson) — while
     *  kimi's is flat. The first cut read the top level only, so every real codex and grok exchange was
     *  refused as tokenless. A JSON null is not a token either: JsonNull is a JsonPrimitive whose
     *  content is the string "null", the same trap [errorCode] already steps around. */
    private fun accessTokenOf(obj: JsonObject): String? {
        val nested = (obj["tokens"] as? JsonObject)?.get("access_token")
        val primitive = (obj["access_token"] ?: nested) as? JsonPrimitive
        return primitive?.takeUnless { it is JsonNull }?.content
    }

    internal fun formHeaders(request: HttpRequestBuilder, identityHeaders: Map<String, String>) {
        request.header("Content-Type", "application/x-www-form-urlencoded")
        request.header("Accept", "application/json")
        identityHeaders.forEach { (k, v) -> request.header(k, v) }
    }

    // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): provider error bodies are frequently not JSON (HTML, plain prose), so 'no error code' is the normal reading; the caller already prints the status and the sanitized body.
    internal fun errorCode(body: String): String = Cancellables.runCatchingCancellable {
        (loginJson.parseToJsonElement(body) as? JsonObject)?.let { obj ->
            (obj["error"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
        }
    }.getOrNull().orEmpty()

    internal fun sanitize(s: String): String = s.filter { !it.isISOControl() }.take(ERR_BODY_CAP)
}

// V4-122: ONE error-body truncation width for the login flow, read by both files that render one
// (this one and OAuthLoginFlow). Two widths would make the SAME upstream error read differently
// depending on which path surfaced it, which is the whole reason the checker held the name as a
// scar. Internal rather than private so the sibling in this package reads this declaration instead
// of keeping its own copy.
internal const val ERR_BODY_CAP = 300
