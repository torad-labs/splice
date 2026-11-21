// NEW: custom-provider (issue #680, cp-credential); in :app since Nov 21, 2026, as :core starts no process (the
// module law's OS-escape rule). One run of a provider's `credential_command`, with /bin/sh -c and
// the daemon's own environment, to which splice adds nothing it holds; stdin is closed, so a command that would prompt
// fails instead of waiting. What it must print (decided once for the feature): one JSON object
// `{"token": "<key>", "expires_at": "<RFC 3339>"}`, expires_at optional, or one bare non-empty line, the key with no
// expiry (Claude Code's apiKeyHelper form), so a helper that already exists works. Anything else, a non-zero exit, or
// no answer within 30 seconds is a failed run, in its stderr's words.
package splice.app.provider

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.auth.COMMAND_EXPIRES_FIELD
import splice.core.auth.CredentialRun
import splice.core.auth.CredentialRunResult
import splice.core.auth.CredentialRuns
import splice.core.util.Cancellables
import splice.core.util.ProcessTree
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Every provider's credential command, each run with its blocking wait on [io]. */
internal class CredentialCommands(private val io: CoroutineDispatcher) : CredentialRuns {
    override fun of(command: String): CredentialRun = CredentialCommand(command, io)
}

/** Runs [command] as a provider's credential command, its blocking wait on [io]. */
internal class CredentialCommand(private val command: String, private val io: CoroutineDispatcher) : CredentialRun {
    override suspend fun invoke(): CredentialRunResult = withContext(io) { ran() }

    private fun ran(): CredentialRunResult = Cancellables.runCatchingCancellable {
        ProcessBuilder(COMMAND_SHELL, "-c", command).redirectInput(File(NO_INPUT)).start()
    }.fold(
        onSuccess = ::finished,
        onFailure = { failure ->
            CredentialRunResult.Failed("could not start: ${failure.message.orEmpty()}", said = false)
        },
    )

    /** What [process] answered: its key, or its own words when it timed out, failed or printed none. */
    private fun finished(process: Process): CredentialRunResult {
        val out = CompletableFuture.supplyAsync { process.inputStream.readAllBytes().decodeToString() }
        val err = CompletableFuture.supplyAsync { process.errorStream.readAllBytes().decodeToString() }
        val exited = process.waitFor(COMMAND_TIMEOUT_S, TimeUnit.SECONDS)
        if (!exited) ProcessTree(process).kill()
        val said = words(if (exited) err.get() else err.getNow(""))
        return when {
            !exited -> failed(said, "did not answer within $COMMAND_TIMEOUT_S seconds")
            process.exitValue() != 0 -> failed(said, "exited ${process.exitValue()}")
            else -> parsed(out.get().trim()) ?: failed(said, "printed no credential")
        }
    }

    /** [printed] when the command printed it; [own] when it printed nothing. [own] is splice's, so a turn is not
     *  refused in it. */
    private fun failed(printed: String, own: String): CredentialRunResult.Failed = when {
        printed.isEmpty() -> CredentialRunResult.Failed(own, said = false)
        else -> CredentialRunResult.Failed(printed, said = true)
    }

    /** One JSON object with a token, or one bare line; null for anything else. */
    private fun parsed(text: String): CredentialRunResult.Key? = when {
        text.isEmpty() -> null
        !text.startsWith("{") -> text.takeIf { '\n' !in it }?.let { CredentialRunResult.Key(it, null) }
        else -> fromJson(text)
    }

    private fun fromJson(text: String): CredentialRunResult.Key? {
        val json = Cancellables.runCatchingCancellable { Json.parseToJsonElement(text) as? JsonObject }
            .fold(onSuccess = { it }, onFailure = { null })
        val token = (json?.get(TOKEN) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.takeIf(String::isNotEmpty) ?: return null
        val expires = (json[COMMAND_EXPIRES_FIELD] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return CredentialRunResult.Key(token, null)
        return Cancellables.runCatchingCancellable { OffsetDateTime.parse(expires).toInstant() }
            .fold(onSuccess = { CredentialRunResult.Key(token, it) }, onFailure = { null })
    }

    private fun words(stderr: String): String = stderr.trim().take(MAX_WORDS)
}

/** The shell a provider's command is given to, its credential command and its sign-in command alike. */
internal const val COMMAND_SHELL: String = "/bin/sh"

/** What such a command reads as its input: nothing, so one that would prompt fails instead of waiting. */
internal const val NO_INPUT: String = "/dev/null"
private const val TOKEN = "token"

// why: a sign-in service that takes longer than this to mint a key is down; the turn says so rather than hanging.
private const val COMMAND_TIMEOUT_S = 30L

// why: what a failed run's words may be in a line or a doctor row, as the feature decided.
private const val MAX_WORDS = 500
