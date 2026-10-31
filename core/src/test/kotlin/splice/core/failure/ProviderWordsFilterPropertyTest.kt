// NEW: failure-handling slice 2. A provider's own words reach a person only through ProviderWordsFilter, so what it
// keeps is what a line can quote. The goal (final-design.md section 6): out of the provider's raw words, keep one plain
// sentence a person can read, with nothing a person cannot use or must not see. Every property runs over generated
// bodies, and the two recorded cases are the provider replies the journal holds.
package splice.core.failure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

private const val RUNS = 400
private const val MAX_KEPT = 160

/** Plain sentences a provider writes about a failure: nothing in them is cut. */
private val SUBJECTS = listOf(
    "Your account", "The model", "This request", "The organization", "Your plan", "The balance", "This key",
    "The selected model", "Your workspace",
)
private val PREDICATES = listOf(
    "has no remaining credit", "is not allowed to use this model", "exceeded its monthly allowance",
    "was stopped by the content filter", "has no active subscription", "cannot use tools in this mode",
    "is not available in your region", "does not support images", "was refused under the usage policy",
)

/** Sentences each holding one thing a person cannot use: the whole sentence goes, whatever else it says. */
private val TAINTED = listOf(
    "Add credits at https://billing.example.com/top-up?ref=usage now." to "https://",
    "Visit www.example.com/billing for the details." to "www.example.com",
    "Contact api.provider.io for help with this account." to "api.provider.io",
    "The gateway at 10.0.4.17:8443 answered instead." to "10.0.4.17",
    "The proxy localhost:11434 refused the call." to "localhost:11434",
    "Your usage window resets at 2026-10-26T05:25:35Z for this plan." to "2026-10-26T05:25",
    "The upstream server returned an unexpected answer." to "upstream",
    "Reason code exceeded_limit was returned for this plan." to "exceeded_limit",
    "Usage credits are required for this model." to "credits are required",
    "Extra usage is required to continue this session." to "extra usage is required",
    "The account state is out_of_credits for now." to "out_of_credits",
    "Code credits_required came back for the request." to "credits_required",
    "The spend state is service_spend_limit_reached today." to "service_spend_limit_reached",
)

/** Sentences of advice to retry or wait: never a person's to act on from splice's line. */
private val ADVICE = listOf(
    "Please retry later.",
    "Try again in a few minutes.",
    "Wait a moment before sending more requests.",
    "Retrying sooner will not help.",
    "You can retry the request after the reset.",
    "Please wait and try again.",
)

/** Bodies that are JSON as a whole. */
private val JSON_BODIES = listOf(
    """{"error":{"message":"Insufficient Balance","type":"insufficient_balance_error"}}""",
    """{"code":"personal-team-blocked:spending-limit","error":"You have run out of credits."}""",
    """[{"message":"bad"}]""",
    """{"detail":"Model not found"}""",
    "42",
    "true",
    "\"Just a quoted string.\"",
)

/** A run of four or more stars between key characters, as a provider masks a key (ForeignCredential). */
private val MASKED = Regex("""[A-Za-z0-9_-]*\*{4,}[A-Za-z0-9_-]*""")

/** Any token of 24 or more letters and digits. */
private val LONG_TOKEN = Regex("""[A-Za-z0-9]{24,}""")

private fun Random.pick(from: List<String>): String = from[nextInt(from.size)]

private fun Random.sentence(): String = "${pick(SUBJECTS)} ${pick(PREDICATES)}."

/** One word of two to six consonant-vowel syllables, which spells none of JSON's literals and no word the filter cuts
 *  (those need a t, a w or two consonants in a row), sometimes capitalized or joined by an underscore. */
private fun Random.word(): String {
    val consonants = "bcdfgklmnprsvz"
    val vowels = "aeiou"
    val syllables = { (1..2 + nextInt(5)).joinToString("") { "${consonants.random(this)}${vowels.random(this)}" } }
    val word = if (nextInt(4) == 0) "${syllables()}_${syllables()}" else syllables()
    return if (nextBoolean()) word.replaceFirstChar(Char::uppercaseChar) else word
}

private fun Random.uuid(): String {
    val hex = "0123456789abcdef"
    fun part(n: Int) = (1..n).map { hex[nextInt(hex.length)] }.joinToString("")
    return "${part(8)}-${part(4)}-${part(4)}-${part(4)}-${part(12)}"
}

private fun Random.alnum(n: Int): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..n).map { chars[nextInt(chars.length)] }.joinToString("")
}

/** An id in parentheses, in the shapes providers write one. */
private fun Random.parenthesisedId(): String = when (nextInt(3)) {
    0 -> "(request_id: ${uuid()})"
    1 -> "(trace id: ${uuid()})"
    else -> "(id=${alnum(12)})"
}

/** A key a provider names: a masked one, or a whole token of 24 or more letters and digits. */
private fun Random.keyToken(): String = when (nextInt(3)) {
    0 -> "sk-test${alnum(2)}" + "*".repeat(4 + nextInt(30)) + alnum(4)
    1 -> "sk-proj-" + alnum(24 + nextInt(20))
    else -> alnum(24 + nextInt(16))
}

private fun words(raw: String): String? = ProviderWordsFilter.of(raw)?.text

class ProviderWordsFilterPropertyTest {
    @Test
    fun `a body that is JSON as a whole yields no words`() {
        val random = Random(1)
        repeat(RUNS) {
            val body = random.pick(JSON_BODIES)
            assertNull(words(body), body)
        }
        assertNull(ProviderWordsFilter.of(null))
        assertNull(words("   "))
    }

    // A provider may say one word: a refusal's category with no explanation, or a bare "Forbidden". It is a sentence a
    // person can read, and only JSON's own values (a number, true, false, null, a quoted string) are JSON as a whole.
    @Test
    fun `a single word is a sentence, not JSON`() {
        val random = Random(7)
        repeat(RUNS) {
            val word = random.word()

            assertEquals("$word.", words(word), word)
        }
        listOf("cyber", "future_category", "Forbidden").forEach { assertEquals("$it.", words(it), it) }
        listOf("null", "false", "-1.5e3").forEach { assertNull(words(it), it) }
    }

    @Test
    fun `a sentence holding a URL, a host, an ISO instant, upstream or a stop phrase is cut, and the rest survives`() {
        val random = Random(2)
        repeat(RUNS) {
            val kept = random.sentence()
            val (tainted, mark) = random.pick(TAINTED.map { "${it.first}|${it.second}" }).split("|")
            val raw = if (random.nextBoolean()) "$tainted $kept" else "$kept $tainted"

            val out = words(raw)

            assertEquals(kept, out, raw)
            assertFalse(out.orEmpty().contains(mark, ignoreCase = true), raw)
        }
        TAINTED.forEach { (tainted, _) -> assertNull(words(tainted), tainted) }
    }

    @Test
    fun `parenthesised ids and key-shaped tokens never survive`() {
        val random = Random(3)
        repeat(RUNS) {
            val subject = random.pick(SUBJECTS)
            val predicate = random.pick(PREDICATES)
            val id = random.parenthesisedId()
            val key = random.keyToken()
            val raw = when (random.nextInt(3)) {
                0 -> "$subject $predicate $id."
                1 -> "$subject $id $predicate."
                else -> "$subject $predicate: $key."
            }

            val out = words(raw)

            assertNotNull(out, raw)
            val kept = out.orEmpty()
            assertTrue(kept.startsWith(subject), "the sentence around the id or key survives: $raw -> $kept")
            assertFalse(id.removePrefix("(").removeSuffix(")") in kept, "$raw -> $kept")
            assertFalse(id.substringAfter(": ").substringAfter("=").removeSuffix(")") in kept, "$raw -> $kept")
            assertFalse(key in kept, "$raw -> $kept")
            assertFalse(MASKED.containsMatchIn(kept), "$raw -> $kept")
            assertFalse(LONG_TOKEN.containsMatchIn(kept), "$raw -> $kept")
            assertFalse('(' in kept || ')' in kept, "$raw -> $kept")
        }
    }

    @Test
    fun `retry advice never survives`() {
        val random = Random(4)
        repeat(RUNS) {
            val kept = random.sentence()
            val advice = random.pick(ADVICE)
            val raw = if (random.nextBoolean()) "$advice $kept" else "$kept $advice"

            assertEquals(kept, words(raw), raw)
        }
        ADVICE.forEach { advice -> assertNull(words(advice), advice) }
    }

    @Test
    fun `dotted field paths never survive`() {
        val random = Random(5)
        repeat(RUNS) {
            val path = "messages.${random.nextInt(900)}.content.${random.nextInt(4)}.text"
            val raw = "${random.pick(SUBJECTS)} ${random.pick(PREDICATES)} at $path."

            val out = words(raw).orEmpty()

            assertFalse(path in out, "$raw -> $out")
            assertFalse(Regex("""\w\.\d""").containsMatchIn(out), "$raw -> $out")
        }
    }

    @Test
    fun `kept words are one sentence of 160 characters or fewer, cut at a word boundary and ending with a period`() {
        val random = Random(6)
        repeat(RUNS) {
            val long = (1..(8 + random.nextInt(40))).joinToString(" ") { random.pick(PREDICATES) }
            val raw = "${random.pick(SUBJECTS)} $long. ${random.sentence()}"
            val first = raw.substringBefore(". ")

            val out = words(raw)

            assertNotNull(out, raw)
            val kept = out.orEmpty()
            assertTrue(kept.length <= MAX_KEPT, "${kept.length}: $kept")
            assertTrue(kept.endsWith("."), kept)
            assertFalse(Regex("""[.!?]\s""").containsMatchIn(kept), "one sentence: $kept")
            val body = kept.removeSuffix(".")
            assertTrue(first.startsWith(body), "a prefix of the first sentence: $kept")
            assertTrue(body.length == first.length || first[body.length] == ' ', "cut at a word boundary: $kept")
            assertFalse('—' in kept, kept)
        }
    }

    // The filter reads only the head of a long body, since a page of tens of kilobytes held a turn's ending for
    // minutes when its rules read it whole. A sentence that read stops inside is not what the provider said, so no
    // part of it is quoted, wherever the read stops.
    @Test
    fun `a sentence the read stops inside is never quoted as a fragment`() {
        val whole = "Your balance is too low to continue this request today."
        for (padding in 1_500..2_500 step 7) {
            val raw = "The upstream said " + "more ".repeat(padding / 5) + "here. $whole"

            val kept = words(raw)

            assertTrue(kept == null || kept == whole, "the sentence at ${raw.indexOf(whole)}: $kept")
        }
        assertEquals(whole, words("The upstream said more here. $whole"))
    }

    @Test
    fun `an em dash is spoken as a clause break`() {
        assertEquals("The model is busy, try a smaller request.", words("The model is busy — try a smaller request"))
    }

    @Test
    fun `a colon inside a kept sentence is spoken as a clause break, so a line quoting it keeps one colon`() {
        assertEquals(
            "Invalid value, the tool name must be shorter.",
            words("Invalid value: the tool name must be shorter"),
        )
    }

    @Test
    fun `DeepSeek's 402 of Oct 24, 11 08 11 PM CT yields Insufficient Balance with its request id dropped`() {
        // The journal, Oct 24 23:08:11.149 CT, claude-deepseek: the error.message of
        // {"error":{"message":"Insufficient Balance (request_id: 49d5cc56-b2b7-42ed-8485-ac06076e79d4)",
        //  "type":"insufficient_balance_error","param":null,"code":"invalid_request_error"}}
        val raw = "Insufficient Balance (request_id: 49d5cc56-b2b7-42ed-8485-ac06076e79d4)"

        assertEquals("Insufficient Balance.", words(raw))
    }

    @Test
    fun `xAI's 402 of Oct 20, 10 27 59 PM CT yields its credits sentence with its sentence of URLs dropped`() {
        // The journal, Oct 20 22:27:59.234 CT, claude-grok: the error string of
        // {"code":"personal-team-blocked:spending-limit","error":"You have run out of credits or need a Grok
        //  subscription. Add credits at https://grok.com/?_s=usage or upgrade at https://grok.com/supergrok."}
        val raw = "You have run out of credits or need a Grok subscription. Add credits at " +
            "https://grok.com/?_s=usage or upgrade at https://grok.com/supergrok."

        assertEquals("You have run out of credits or need a Grok subscription.", words(raw))
    }
}
