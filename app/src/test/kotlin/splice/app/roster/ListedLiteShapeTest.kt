// Fix 5 (Oct 27, artifacts/responses-gaps-2026-10-27/goals.md): every model the backend's list marks
// use_responses_lite gets the lite request shape, the lite header and the lite verbosity, whatever its name, and a
// model the list marks otherwise does not. The name pattern (gpt-5.6 or gpt-6) decides only when no list is known. A
// person sets a head's verbosity in splice.toml, as Codex's model_verbosity, and a head with no setting sends what it
// sent.
//
// What lite means on the wire is Codex's own construction (codex-rs core/src/client.rs): no top-level tools, the tools
// in an additional_tools input item at the head of the input, parallel_tool_calls false, and the
// x-openai-internal-codex-responses-lite header set to "true". Codex reads the flag from this same list, and sends
// model_verbosity, or the model's default verbosity, as text.verbosity.
//
// These drive a real codex head, assembled as the daemon assembles it, through its HTTP route against a loopback
// ChatGPT backend that serves the list and records each turn.
package splice.app.roster

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.core.wire.HttpStatus
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val ACCOUNT = "synthetic-account-lite"
private const val LITE_HEADER = "x-openai-internal-codex-responses-lite"

/** Outside the gpt-5.6 / gpt-6 pattern, and marked lite by the backend's list, as gpt-reserve is. */
private const val LISTED_LITE = "gpt-reserve"

/** Outside the pattern, marked lite, and hidden from the picker, as the backend lists codex-auto-review. */
private const val HIDDEN_LITE = "codex-auto-review"

/** Inside the pattern, and marked not lite by the backend's list. */
private const val LISTED_FULL = "gpt-6-synthetic-full"

/** Code mode's one tool, as Codex names it. */
private const val EXEC = "exec"

@Timeout(90)
class ListedLiteShapeTest {

    /** A model the list hides from the picker is reached by naming it among the provider's rows, as the head serves no
     *  model outside its catalog; the list still decides its shape. */
    @Test
    fun `a model the list marks lite gets the lite body, the lite header and low verbosity, whatever its name`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val fixture = SignInRosterFixture(tmp, this, rows = listOf(CODE_MODE_MODEL, HIDDEN_LITE))
        try {
            withTimeout(60.seconds) {
                fixture.signedInWith(
                    listedModel(LISTED_LITE, codeModeOnly = false, lite = true),
                    listedModel(HIDDEN_LITE, codeModeOnly = false, lite = true, visibility = "hide"),
                )
                for (model in listOf(LISTED_LITE, HIDDEN_LITE)) {
                    val sent = fixture.sentTurn(model)
                    assertTrue(sent.liteBody, "$model: the list marks it lite, and its body was ${sent.shape}")
                    assertEquals(false, sent.parallelToolCalls, "$model: a lite turn sends parallel_tool_calls false")
                    assertEquals("true", sent.liteHeader, "$model: the lite header rides a lite turn")
                    assertEquals("low", sent.verbosity, "$model: a lite turn with no setting sends low verbosity")
                }
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a model the list marks not lite gets the full shape, even when its name matches the pattern`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val fixture = SignInRosterFixture(tmp, this)
        try {
            withTimeout(60.seconds) {
                fixture.signedInWith(listedModel(LISTED_FULL, codeModeOnly = false, lite = false))
                val sent = fixture.sentTurn(LISTED_FULL)
                assertTrue(sent.fullBody, "the list marks $LISTED_FULL not lite, and its body was ${sent.shape}")
                assertNull(sent.liteHeader, "no lite header on a turn the list marks not lite")
                assertNull(sent.verbosity, "a turn that is not lite, on a head with no setting, sends no verbosity")
            }
        } finally {
            fixture.close()
        }
    }

    /** Codex looks a model up in the list by its longest listed prefix, and gives one the list does not name its
     *  fallback metadata, which is not lite (codex-rs models-manager manager.rs find_model_by_longest_prefix, and
     *  model_info.rs model_info_from_slug). The pattern decides only when no list is known. */
    @Test
    fun `a known list decides a model it does not name by its longest listed prefix, and otherwise as not lite`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val fixture = SignInRosterFixture(tmp, this, rows = listOf(CODE_MODE_MODEL, "$LISTED_LITE-preview"))
        try {
            withTimeout(60.seconds) {
                fixture.signedInWith(listedModel(LISTED_LITE, codeModeOnly = false, lite = true))

                val extended = fixture.sentTurn("$LISTED_LITE-preview")
                assertTrue(extended.liteBody, "$LISTED_LITE-preview takes $LISTED_LITE's mark: ${extended.shape}")
                assertEquals("true", extended.liteHeader, "the lite header rides the extended id")

                val unnamed = fixture.sentTurn(CODE_MODE_MODEL)
                assertTrue(unnamed.fullBody, "the known list does not name $CODE_MODE_MODEL: ${unnamed.shape}")
                assertNull(unnamed.liteHeader, "no lite header on a model a known list does not name")
                assertNull(unnamed.verbosity, "no verbosity on a model a known list does not name, with no setting")
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `with no list known, the name pattern decides as it does today`(@TempDir tmp: Path) = runBlocking {
        val fixture = SignInRosterFixture(tmp, this, rows = listOf(CODE_MODE_MODEL, LISTED_LITE))
        try {
            withTimeout(60.seconds) {
                fixture.backend.listOutage = true
                fixture.login.signIn(ACCOUNT, "no-list")
                fixture.start()

                val inPattern = fixture.sentTurn(CODE_MODE_MODEL)
                assertTrue(inPattern.liteBody, "$CODE_MODE_MODEL matches the pattern: ${inPattern.shape}")
                assertEquals("true", inPattern.liteHeader, "the pattern's lite turn carries the lite header")
                assertEquals("low", inPattern.verbosity, "the pattern's lite turn sends low verbosity")

                val outside = fixture.sentTurn(LISTED_LITE)
                assertTrue(
                    outside.fullBody,
                    "$LISTED_LITE is outside the pattern and no list says otherwise: ${outside.shape}",
                )
                assertNull(outside.liteHeader, "no lite header outside the pattern with no list")
                assertNull(outside.verbosity, "no verbosity outside the pattern with no list and no setting")
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `code mode's lite check follows the list`(@TempDir tmp: Path) = runBlocking {
        val fixture = SignInRosterFixture(tmp, this)
        try {
            withTimeout(60.seconds) {
                fixture.signedInWith(
                    listedModel(LISTED_LITE, codeModeOnly = true, lite = true),
                    listedModel(LISTED_FULL, codeModeOnly = true, lite = false),
                )
                val lite = fixture.sentTurn(LISTED_LITE)
                assertTrue(EXEC in lite.offered, "$LISTED_LITE is code_mode_only and listed lite: ${lite.offered}")

                val full = fixture.sentTurn(LISTED_FULL)
                assertTrue(
                    EXEC !in full.offered && "synthetic_tool" in full.offered,
                    "$LISTED_FULL is listed not lite, so code mode's lite check refuses it: offered ${full.offered}",
                )
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a codex head's verbosity setting rides each of its turns, lite or not`(@TempDir tmp: Path) = runBlocking {
        val fixture = SignInRosterFixture(tmp, this, overrides = mapOf("verbosity" to "high"))
        try {
            withTimeout(60.seconds) {
                fixture.signedInWith(
                    listedModel(LISTED_LITE, codeModeOnly = false, lite = true),
                    listedModel(LISTED_FULL, codeModeOnly = false, lite = false),
                )
                val lite = fixture.sentTurn(LISTED_LITE)
                assertTrue(lite.liteBody, "the setting leaves the shape to the list: ${lite.shape}")
                assertEquals("high", lite.verbosity, "the head's setting wins over the lite turn's low")

                val full = fixture.sentTurn(LISTED_FULL)
                assertTrue(full.fullBody, "the setting leaves the shape to the list: ${full.shape}")
                assertEquals("high", full.verbosity, "the head's setting rides a turn that is not lite too")
            }
        } finally {
            fixture.close()
        }
    }
}

/** Signs in to an account whose list is [rows], then starts the daemon, so the head's first turn reads that list. */
private suspend fun SignInRosterFixture.signedInWith(vararg rows: String) {
    backend.serve(login.accessToken(ACCOUNT, "listed"), rows.toList())
    login.signIn(ACCOUNT, "listed")
    start()
}

/** One Claude Code turn on [model], answered, and what the head sent the backend for it. */
private fun SignInRosterFixture.sentTurn(model: String): SentTurn {
    val before = backend.turns.size
    val turn = turn(model)
    assertEquals(HttpStatus.OK, turn.statusCode(), "$model: ${turn.body()}")
    assertTrue("message_stop" in turn.body(), "$model: ${turn.body()}")
    assertEquals(before + 1, backend.turns.size, "$model: one request reached the backend")
    return SentTurn(backend.turns.last(), backend.turnHeaders.last())
}

/** One request the head sent, read in the goal's terms. Only the lite header is ever read from [headers], which also
 *  carry the sign-in's bearer. */
private class SentTurn(body: String, private val headers: Map<String, String>) {
    private val json: JsonObject = Json.parseToJsonElement(body).jsonObject
    private val firstInputType: String? =
        ((json["input"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("type")?.jsonPrimitive?.content

    /** Codex's lite construction: no top-level tools, and the tools in an additional_tools item first in the input. */
    val liteBody: Boolean get() = "tools" !in json && firstInputType == "additional_tools"

    /** The full shape: the tools at the top level, and no additional_tools item. */
    val fullBody: Boolean get() = json["tools"] is JsonArray && firstInputType != "additional_tools"

    val parallelToolCalls: Boolean?
        get() = (json["parallel_tool_calls"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
    val verbosity: String? get() = ((json["text"] as? JsonObject)?.get("verbosity") as? JsonPrimitive)?.content
    val liteHeader: String? get() = headers[LITE_HEADER]
    val offered: Set<String> get() = offeredTools(json.toString())

    /** The fields that say which shape went out, for a failure's message. */
    val shape: String get() = "top-level tools=${"tools" in json}, first input item=$firstInputType"
}
