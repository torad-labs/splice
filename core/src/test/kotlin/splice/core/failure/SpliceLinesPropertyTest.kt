// NEW: failure-handling slice 2. Every line a person reads comes from SpliceLines, so its properties are the person's:
// one plain [splice] line of one or two sentences within 240 characters, holding nothing a person cannot use or must
// not see, saying a reset the same way in the line and the trace, in the machine's zone with its abbreviation,
// quoting a provider only where the provider's verdict is the news, and saying Claude Code sends the turn again only
// when the answer is one the client re-sends (final-design.md section 10, the grammar). Every property runs over
// generated leaves, positions, wire answers, provider words, head commands, instants and zones, and the provider words
// are generated from the raw bodies providers send: JSON, URLs, host:port, ISO instants, em dashes, upstream, dotted
// field paths, request ids, masked keys and Claude Code's stop phrases.
package splice.core.failure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.core.util.LocalTimeText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random
import kotlin.reflect.KClass

private const val RUNS = 2_500
private const val MAX_CHARS = 240
private const val SAYS_RESEND = "Claude Code sends this turn again."
private const val COMPACT_TRIGGER = "prompt is too long"

/** What a line must not say once the person has seen the answer begin: that the provider sent nothing. */
private val SENT_NOTHING = listOf("before answering", "before sending anything", "did not start answering")

/** Launch commands of the shipped heads, and the ones operators named (final-design.md section 10, subject). */
private val HEADS = listOf(
    "claude-muse", "claudex", "claudeor", "claude-glml53", "claude-deepseek", "claude-kimi", "claude-splice",
    "claude-openrouter", "claude-grok",
)
private val LOGINS = listOf(null, "claudex login", "claude-muse login", "splice key set OPENROUTER_API_KEY")
private val PROVIDERS = listOf(null, "DeepSeek", "xAI", "Meta", "Anthropic", "OpenRouter")
private val MODELS = listOf("glm-5.3", "gpt-5.6", "kimi-k2-thinking", "deepseek-v4", "claude-opus-5", "qwen3:32b")
private val ZONES = listOf(
    "America/Chicago", "UTC", "Europe/Berlin", "Asia/Tokyo", "America/New_York", "Australia/Sydney", "Asia/Kolkata",
    "America/Sao_Paulo", "Pacific/Auckland",
).map(ZoneId::of)
private val STATUSES = listOf(null, 400, 401, 403, 404, 408, 413, 429, 502, 503, 529)

/** Budget limits as splice spells an amount (ApiCostText.limit), which is what a Decline.Budget carries. */
private val DOLLARS = listOf("$20.00", "$1.50", "$1,234.56")

/** Plain sentences a provider writes, each long and particular enough that finding it in a line means it was quoted. */
private val PLAIN = listOf(
    "Your organization has no remaining prepaid credit",
    "The selected model is not available for this account tier",
    "This request was stopped by the content moderation system",
    "Your subscription does not include access to this model",
    "Extra inputs are not permitted on the tool definitions",
    "The model produced output that violated the usage policy",
    "Invalid value: the tool name must be shorter than this",
)

private val STOP_PHRASES = listOf(
    "service_spend_limit_reached",
    "exceeded_limit",
    "credits_required",
    "usage credits are required",
    "extra usage is required",
    "out_of_credits",
)

/** What no line may hold (the grammar's never list), each with the name a failure reports. */
private val NEVER: List<Pair<String, Regex>> = listOf(
    "an internal code" to Regex("""\[SPLICE-"""),
    "upstream" to Regex("""(?i)\bupstream\b"""),
    "Reason:" to Regex("""Reason:"""),
    "attempt" to Regex("""(?i)\battempt"""),
    "a count of tries" to Regex("""(?i)\b\d+\s*(?:x|times|tries|retries)\b|\b\d+/\d+\b"""),
    "a status or items dump" to Regex("""status=|items=\["""),
    "braces" to Regex("""[{}]"""),
    "a host" to Regex("""(?i)\b(?:(?=[a-z0-9-]*[a-z])[a-z0-9-]+\.)+[a-z]{2,24}\b"""),
    "a host and port" to Regex("""(?i)\blocalhost\b|\b[a-z0-9-]*[a-z][a-z0-9-]*:\d{2,5}\b"""),
    "a URL" to Regex("""://|\bwww\."""),
    "an ISO instant" to Regex("""\d{4}-\d{2}-\d{2}"""),
    "a field path" to Regex("""\b[A-Za-z_]\w*(?:\.\w+)*\.\d+\.\w+|\b[A-Za-z_]\w*(?:\.[A-Za-z_]\w*){2,}\b"""),
    "an em dash" to Regex("—"),
    "the word log" to Regex("""(?i)\blog\b"""),
    "a request id" to Regex("""(?i)request[ _-]?id|\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b"""),
    "a key" to Regex("""\*{4,}|\bsk-|[A-Za-z0-9]{24,}"""),
    "a stop phrase" to Regex(STOP_PHRASES.joinToString("|", prefix = "(?i)") { Regex.escape(it) }),
)

/** The leaves whose provider words a line may quote (the grammar's quote rule). */
private val QUOTED: Set<KClass<out FailureCause>> = setOf(
    FailureCause.Provider.Refused::class,
    FailureCause.Provider.Rejected::class,
    FailureCause.Provider.Forbidden::class,
    FailureCause.Provider.NeedsPayment::class,
    FailureCause.Provider.Unrecognized::class,
)

/** A provider's raw words, and the fragments of a secret they carry that no line may show. */
private data class Raw(val text: String, val secrets: List<String>)

/** A generated ending: what SpliceLines is asked to word. */
private data class LineCase(val cause: FailureCause, val at: Position, val answer: WireAnswer, val facts: LineFacts)

private fun <T> Random.pick(from: List<T>): T = from[nextInt(from.size)]

private fun Random.alnum(n: Int): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return (1..n).map { chars[nextInt(chars.length)] }.joinToString("")
}

private fun Random.hex(n: Int): String = (1..n).map { "0123456789abcdef"[nextInt(16)] }.joinToString("")

/** What a provider sends, in the shapes the grammar names: a URL, a host and port, an ISO instant, an em dash. */
private fun Random.raw(): Raw {
    val plain = "${pick(PLAIN)}."
    return when (nextInt(12)) {
        0 -> Raw("""{"error":{"message":"$plain","type":"invalid_request_error"}}""", emptyList())
        1 -> Raw("$plain Add credits at https://billing.example.com/top-up?ref=${alnum(6)} now.", emptyList())
        2 -> Raw("$plain The gateway at api.provider.io:8443 answered instead.", emptyList())
        3 -> Raw("$plain Your usage window resets at 2026-10-26T05:25:35Z.", emptyList())
        4 -> Raw("${pick(PLAIN)} — contact your administrator.", emptyList())
        5 -> Raw("The upstream server returned an error. $plain", emptyList())
        else -> rawTail(plain, nextInt(6))
    }
}

/** The rest of the shapes: a field path, a request id, a masked key, a stop phrase, advice to retry, plain words. */
private fun Random.rawTail(plain: String, which: Int): Raw {
    val keyHead = "sk-test${alnum(4)}"
    val keyTail = alnum(4)
    return when (which) {
        0 -> Raw("$plain Check messages.${nextInt(900)}.content.${nextInt(4)}.text first.", emptyList())
        1 -> Raw("${pick(PLAIN)} (request_id: ${hex(8)}-${hex(4)}-${hex(4)}-${hex(4)}-${hex(12)}).", emptyList())
        2 -> Raw("Incorrect API key provided: $keyHead****************$keyTail.", listOf(keyHead, keyTail))
        3 -> Raw("$plain Reason code ${pick(STOP_PHRASES)} came back.", emptyList())
        4 -> Raw("$plain Please retry later.", emptyList())
        else -> Raw(plain, emptyList())
    }
}

private fun Random.instant(): Instant = Instant.ofEpochSecond(1_767_225_600L + nextLong(63_072_000L))

private fun Random.lift(now: Instant): Lift =
    if (nextInt(3) == 0) {
        Lift.Unstated
    } else {
        Lift.At(now.plusSeconds(nextLong(864_000L)), nextBoolean(), pick(LiftSource.entries))
    }

/** The leaves a provider reports about the turn's fate: a limit, a payment, a load, a fault, a size, a login. */
private fun Random.providerCause(now: Instant, said: ProviderWords?): FailureCause.Provider = when (nextInt(13)) {
    0 -> FailureCause.Provider.Limited(lift(now), pick(LimitWindow.entries + null), said)
    1 -> FailureCause.Provider.Overloaded(lift(now), nextBoolean(), said)
    2 -> FailureCause.Provider.ServerError(pick(ServerFault.entries), said)
    3 -> FailureCause.Provider.NeedsPayment(lift(now), nextBoolean(), said)
    4 -> FailureCause.Provider.Forbidden(said)
    5 -> FailureCause.Provider.AuthRejected(pick(LoginOwner.entries), said)
    6 -> FailureCause.Provider.TooLong(pick(listOf(null, 131_072L, 262_144L)), nextBoolean(), said)
    else -> providerVerdict(nextInt(6), said)
}

/** The leaves a provider reports about the request itself, and its empty answers. */
private fun Random.providerVerdict(which: Int, said: ProviderWords?): FailureCause.Provider = when (which) {
    0 -> FailureCause.Provider.Rejected(nextBoolean(), said)
    1 -> FailureCause.Provider.ModelMissing(pick(MODELS), said)
    2 -> FailureCause.Provider.Refused(pick(RefusedBy.entries), said)
    3 -> FailureCause.Provider.Empty(nextBoolean(), said)
    4 -> FailureCause.Provider.ToolTear(nextBoolean(), nextBoolean(), said)
    else -> FailureCause.Provider.Unrecognized(nextBoolean(), said)
}

private fun Random.pathCause(): FailureCause.Path = when (nextInt(3)) {
    0 -> FailureCause.Path.Unreachable(pick(Unreach.entries), nextBoolean())
    1 -> FailureCause.Path.Torn(pick(Tear.entries), nextBoolean())
    else -> FailureCause.Path.Stalled(pick(Stall.entries))
}

private fun Random.decline(now: Instant): Decline = pick(
    listOf(
        Decline.TooLarge(pick(listOf(null, 56_623_104L, 33_554_433L, 9_000_000L)), 33_554_432L),
        Decline.HeapBusy,
        Decline.Budget(limit = pick(DOLLARS), resetsAt = now.plusSeconds(nextLong(86_400L))),
        Decline.Preflight(pick(PreflightKind.entries), windowTokens = pick(listOf(131_072L, 262_144L))),
        Decline.BadBody(readBytes = 12L, declaredBytes = pick(listOf(null, 12L, 40L))),
        Decline.ReadTimeout,
        Decline.UnknownModel(pick(MODELS)),
        Decline.ForeignHost,
        Decline.Unfit(weight = pick(listOf(212_992L, 96_468_992L)), limit = pick(listOf(131_072L, 67_108_864L))),
        Decline.BadLocalKey,
        Decline.SpliceKeyAsLogin(
            pick(
                listOf(
                    emptyList(),
                    listOf("ANTHROPIC_API_KEY"),
                    listOf("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_CUSTOM_HEADERS"),
                    listOf("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY", "ANTHROPIC_CUSTOM_HEADERS"),
                ),
            ),
        ),
        Decline.LivenessProbe,
        Decline.NoSharedLogin,
        Decline.Untranslatable(pick(RequestFeature.entries)),
    ),
)

/** The leaves splice decides before or around a provider: a login, capacity, a refusal, a budget, a spent hold. */
private fun Random.spliceCause(now: Instant, said: ProviderWords?): FailureCause.Splice = when (nextInt(9)) {
    0 -> FailureCause.Splice.NoLogin(pick(LOGINS))
    1 -> FailureCause.Splice.AtCapacity(pick(listOf(1, 14, 100)))
    2 -> FailureCause.Splice.Declined(decline(now))
    3 -> FailureCause.Splice.TurnBudgetSpent(pick(listOf(900_000L, 1_800_000L, 45_000L)), nextBoolean())
    4 -> FailureCause.Splice.HoldOutlasted(pick(Outlast.entries), providerCause(now, said), maybeReset(now))
    else -> spliceStop(nextInt(4))
}

/** A next stated reset, or none. */
private fun Random.maybeReset(now: Instant): Instant? =
    if (nextBoolean()) null else now.plusSeconds(nextLong(864_000L))

/** The leaves that stop a turn inside splice. */
private fun Random.spliceStop(which: Int): FailureCause.Splice = when (which) {
    0 -> FailureCause.Splice.CompactionUnfinished
    1 -> FailureCause.Splice.Stopped(pick(StoppedBy.entries))
    2 -> FailureCause.Splice.Protocol(pick(ProtocolFault.entries))
    else -> FailureCause.Splice.Internal(nextBoolean(), nextBoolean())
}

private fun Random.cause(now: Instant, said: ProviderWords?): FailureCause = when (nextInt(3)) {
    0 -> providerCause(now, said)
    1 -> pathCause()
    else -> spliceCause(now, said)
}

private fun Random.facts(now: Instant): LineFacts = LineFacts(
    head = pick(HEADS),
    loginCommand = pick(LOGINS),
    providerName = pick(PROVIDERS),
    local = nextBoolean(),
    request = RequestFacts(compaction = nextInt(4) == 0, firstExchange = nextInt(4) == 0),
    now = now,
)

/** The answer ClientWire gives, or any answer at all: a line must be right about whichever it is handed. */
private fun Random.answer(cause: FailureCause, at: Position): WireAnswer =
    if (nextBoolean()) {
        ClientWire.answer(cause, at)
    } else {
        WireAnswer(pick(STATUSES), pick(ErrorType.entries), pick(listOf(null, true, false)))
    }

private fun Random.case(now: Instant, said: ProviderWords?): LineCase {
    val cause = cause(now, said)
    val at = Position(pick(Moment.entries), nextBoolean())
    return LineCase(cause, at, answer(cause, at), facts(now))
}

private fun Random.recovery(): Recovery = when (nextInt(4)) {
    0 -> Recovery.RetryIn(pick(listOf(0L, 400L, 15_000L, 60_000L, 600_000L, 3_600_000L, 7_728_000L)))
    1 -> Recovery.Resume
    2 -> Recovery.AskAgain
    else -> Recovery.OtherLogin
}

/** The leaves the client may send again on today's wire: family R (section 11), with the limit and payment endings
 *  slices 2 to 4 still answer that way (the interim lines). HoldOutlasted's line is the held cause's (slice 5). */
private fun resendable(cause: FailureCause): Boolean? = when (cause) {
    is FailureCause.Provider ->
        cause::class in RESENDABLE_PROVIDER ||
            (cause is FailureCause.Provider.Unrecognized && !cause.clientError)
    is FailureCause.Path -> true
    is FailureCause.Splice.HoldOutlasted -> null
    is FailureCause.Splice -> resendableSplice(cause)
}

private val RESENDABLE_PROVIDER: Set<KClass<out FailureCause>> = setOf(
    FailureCause.Provider.Limited::class,
    FailureCause.Provider.NeedsPayment::class,
    FailureCause.Provider.Overloaded::class,
    FailureCause.Provider.ServerError::class,
    FailureCause.Provider.Empty::class,
    FailureCause.Provider.ToolTear::class,
)

private fun resendableSplice(cause: FailureCause.Splice): Boolean = when (cause) {
    is FailureCause.Splice.Declined -> cause.why == Decline.HeapBusy || cause.why == Decline.ReadTimeout
    is FailureCause.Splice.Stopped -> cause.by != StoppedBy.CONSOLE
    is FailureCause.Splice.Protocol -> cause.fault == ProtocolFault.CODE_MODE_START
    is FailureCause.Splice.NoLogin -> false
    is FailureCause.Splice.AtCapacity, is FailureCause.Splice.TurnBudgetSpent, is FailureCause.Splice.HoldOutlasted,
    FailureCause.Splice.CompactionUnfinished, is FailureCause.Splice.Internal,
    -> true
}

/** Whether the line must carry the phrase that makes Claude Code compact: a T line that is not a compaction or a first
 *  exchange (section 11, "T's line says prompt is too long"). */
private fun compacts(cause: FailureCause, request: RequestFacts): Boolean =
    if (cause is FailureCause.Provider.TooLong) {
        !request.compaction && !request.firstExchange
    } else {
        ((cause as? FailureCause.Splice.Declined)?.why as? Decline.Preflight)?.kind == PreflightKind.COMPACTABLE
    }

/** The one-line, one-or-two-sentence, nothing-unusable properties every line keeps, whatever wrote it. A sentence's
 *  subject is a capital, the head's command, splice, or the provider as the provider spells itself: "xAI did not accept
 *  this session's login" opens with a name, not with our words (section 10, subject and provider). */
private fun assertReadable(line: SpliceLine, facts: LineFacts, secrets: List<String>, about: String) {
    val text = line.text
    assertTrue(text.startsWith(SPLICE_PREFIX), "opens with the prefix: $about -> $text")
    assertTrue(text.length <= MAX_CHARS, "${text.length} characters: $about -> $text")
    assertFalse(text.contains('\n') || text.contains('\r'), "one line: $about -> $text")
    assertTrue(text.endsWith("."), "ends a sentence: $about -> $text")
    val sentences = line.sentence.split(Regex("""(?<=[.!?])\s+"""))
    assertTrue(sentences.size in 1..2, "${sentences.size} sentences: $about -> $text")
    val names = listOfNotNull(facts.head, facts.providerName, "splice")
    sentences.forEach { sentence ->
        val opens = sentence.first().isUpperCase() || names.any(sentence::startsWith)
        assertTrue(opens, "a sentence opens with a capital, the head, splice or the provider: $about -> $text")
    }
    assertTrue(Regex(": ").findAll(text).count() <= 1, "at most one colon: $about -> $text")
    NEVER.forEach { (what, rule) -> assertFalse(rule.containsMatchIn(text), "holds $what: $about -> $text") }
    secrets.forEach { assertFalse(it in text, "holds a fragment of a key, $it: $about -> $text") }
}

class SpliceLinesPropertyTest {
    @Test
    fun `every line is one splice line of one or two sentences within 240 characters`() {
        forEveryLine(seed = 1) { line, facts, _, about -> assertReadable(line, facts, emptyList(), about) }
    }

    @Test
    fun `no line holds a code, a count of tries, a host, a URL, an instant, a path, an id, a key or a stop phrase`() {
        forEveryLine(seed = 2) { line, facts, secrets, about -> assertReadable(line, facts, secrets, about) }
    }

    @Test
    fun `a reset reads the same in the line and the trace, in the machine's zone with its abbreviation`() {
        val random = Random(3)
        repeat(RUNS) {
            val zone = random.pick(ZONES)
            val lines = SpliceLines(LocalTimeText(zone))
            val now = random.instant()
            val reset = now.plusSeconds(random.nextLong(864_000L))
            val window = random.pick(LimitWindow.entries + null)
            val cause = FailureCause.Provider.Limited(Lift.At(reset, random.nextBoolean(), LiftSource.BODY), window)
            val at = Position(random.pick(Moment.entries), random.nextBoolean())
            val facts = random.facts(now)

            val line = lines.ending(cause, at, random.answer(cause, at), facts)

            val said = LocalTimeText(zone).at(reset.epochSecond)
            val abbreviation = DateTimeFormatter.ofPattern("zzz", Locale.US).withZone(zone).format(reset)
            assertTrue(said.endsWith(" $abbreviation"), said)
            assertTrue(said in line.text, "the line says the reset as $said: ${line.text}")
            assertTrue(said in line.sentence, "the trace says the reset as $said: ${line.sentence}")
            assertEquals(line.text, SPLICE_PREFIX + line.sentence)
        }
    }

    @Test
    fun `a wait of an hour or more reads as a clock time in the machine's zone`() {
        val random = Random(4)
        repeat(RUNS) {
            val zone = random.pick(ZONES)
            val now = random.instant()
            val waitMs = 3_600_000L + random.nextLong(86_400_000L)
            val facts = random.facts(now)
            val cause = random.cause(now, null)

            val line = SpliceLines(LocalTimeText(zone)).waiting(WaitLine(cause, Recovery.RetryIn(waitMs)), facts)

            val resumes = LocalTimeText(zone).at(now.plusMillis(waitMs).epochSecond)
            assertTrue(resumes in line.text, "tries again at $resumes: ${line.text}")
        }
    }

    @Test
    fun `provider words appear only in refused, rejected, forbidden, payment and unrecognized lines`() {
        val random = Random(5)
        val lines = SpliceLines(LocalTimeText(ZoneId.of("America/Chicago")))
        repeat(RUNS) {
            val now = random.instant()
            val words = ProviderWordsFilter.of("${random.pick(PLAIN)}.")
            val case = random.case(now, words)
            val spoken = words?.text?.removeSuffix(".").orEmpty()

            val line = lines.ending(case.cause, case.at, case.answer, case.facts)

            val quotes = case.cause::class in QUOTED
            if (!quotes) assertFalse(spoken in line.text, "quoted by a ${case.cause}: ${line.text}")
            val subscription = (case.cause as? FailureCause.Provider.NeedsPayment)?.subscription == true
            if (quotes && !subscription) assertTrue(spoken in line.text, "${case.cause} quotes its words: ${line.text}")
            val wait = lines.waiting(WaitLine(case.cause, random.recovery()), case.facts)
            assertFalse(spoken in wait.text, "a wait line quotes nobody: ${wait.text}")
        }
    }

    @Test
    fun `an R line says Claude Code sends the turn again only when its answer is one the client re-sends`() {
        val random = Random(6)
        val lines = SpliceLines(LocalTimeText(ZoneId.of("America/Chicago")))
        repeat(RUNS * 2) {
            val case = random.case(random.instant(), null)

            val line = lines.ending(case.cause, case.at, case.answer, case.facts)

            val says = SAYS_RESEND in line.text
            val about = "${case.cause} at ${case.at}, ${case.answer}"
            if (says) assertTrue(case.answer.clientResends, "says the client re-sends, which it does not: $about")
            when (resendable(case.cause)) {
                true -> if (case.answer.clientResends) assertTrue(says, "an R line names the re-send: $about")
                false -> assertFalse(says, "a line that stops promises no re-send: $about -> ${line.text}")
                null -> Unit
            }
            val waits = lines.waiting(WaitLine(case.cause, random.recovery()), case.facts)
            assertFalse(SAYS_RESEND in waits.text, "a wait line is the turn's own retry: ${waits.text}")
        }
    }

    @Test
    fun `only a T line that is not a compaction or a first exchange says the prompt is too long`() {
        val random = Random(7)
        val lines = SpliceLines(LocalTimeText(ZoneId.of("America/Chicago")))
        repeat(RUNS * 2) {
            val case = random.case(random.instant(), null)

            val line = lines.ending(case.cause, case.at, case.answer, case.facts)

            assertEquals(
                compacts(case.cause, case.facts.request),
                COMPACT_TRIGGER in line.text,
                "${case.cause}, ${case.facts.request} -> ${line.text}",
            )
        }
    }

    @Test
    fun `a line said once the answer began never says the provider sent nothing`() {
        val random = Random(8)
        val lines = SpliceLines(LocalTimeText(ZoneId.of("America/Chicago")))
        val answering = Position(Moment.MID_CONTENT, streamBegan = true)
        repeat(RUNS * 2) {
            val now = random.instant()
            val cause = random.cause(now, null)
            val facts = random.facts(now)

            val ending = lines.ending(cause, answering, random.answer(cause, answering), facts)
            val resumes = lines.waiting(WaitLine(cause, Recovery.Resume), facts)

            listOf(ending, resumes).forEach { line ->
                SENT_NOTHING.forEach { said ->
                    assertFalse(said in line.text, "$cause after the answer began says \"$said\": ${line.text}")
                }
            }
        }
    }

    @Test
    fun `a size or a limit under a kilobyte is said in bytes, as it is`() {
        val random = Random(9)
        val lines = SpliceLines(LocalTimeText(ZoneId.of("America/Chicago")))
        val atAdmission = Position(Moment.ADMISSION, streamBegan = false)
        repeat(RUNS) {
            val limit = 1L + random.nextLong(1_022L)
            val bytes = random.pick(listOf(null, limit + 1L + random.nextLong(1_023L - limit)))
            val cause = FailureCause.Splice.Declined(Decline.TooLarge(bytes, limit))
            val facts = random.facts(random.instant())
            val said = lines.ending(cause, atAdmission, ClientWire.answer(cause, atAdmission), facts)
            assertTrue("the ${inBytes(limit)} splice accepts" in said.text, "a $limit-byte limit: ${said.text}")
            bytes?.let { assertTrue("is ${inBytes(it)}, more than" in said.text, "a $it-byte request: ${said.text}") }
        }
    }

    @Test
    fun `a request is said to have arrived incomplete only when fewer bytes arrived than it declared`() {
        val random = Random(10)
        val lines = SpliceLines(LocalTimeText(ZoneId.of("America/Chicago")))
        val atAdmission = Position(Moment.ADMISSION, streamBegan = false)
        repeat(RUNS) {
            val read = random.nextLong(1_000_000L)
            val declared = random.pick(listOf(null, read, read + 1L + random.nextLong(1_000_000L), read / 2))
            val cause = FailureCause.Splice.Declined(Decline.BadBody(read, declared))
            val facts = random.facts(random.instant())
            val said = lines.ending(cause, atAdmission, ClientWire.answer(cause, atAdmission), facts)
            val short = declared != null && read < declared
            assertEquals(short, "incomplete" in said.text, "$read bytes read of $declared declared: ${said.text}")
        }
    }

    private fun inBytes(n: Long): String = if (n == 1L) "1 byte" else "$n bytes"

    /** Runs [check] over endings, wait lines, quiet lines and notes built from generated inputs. */
    private fun forEveryLine(seed: Int, check: (SpliceLine, LineFacts, List<String>, String) -> Unit) {
        val random = Random(seed)
        repeat(RUNS) {
            val lines = SpliceLines(LocalTimeText(random.pick(ZONES)))
            val now = random.instant()
            val raw = random.raw()
            val case = random.case(now, ProviderWordsFilter.of(raw.text))
            val facts = case.facts
            val about = "${case.cause} at ${case.at}, ${case.answer}, $facts"
            check(lines.ending(case.cause, case.at, case.answer, facts), facts, raw.secrets, about)
            val wait = WaitLine(case.cause, random.recovery())
            check(lines.waiting(wait, facts), facts, raw.secrets, "$wait, $facts")
            val quiet = QuietTurn(random.nextLong(7_200_000L), random.nextBoolean(), random.nextBoolean())
            check(lines.quiet(quiet, facts), facts, raw.secrets, "$quiet")
            check(lines.note(SessionNote.NEW_LOGIN, facts), facts, raw.secrets, "a note")
        }
    }
}
