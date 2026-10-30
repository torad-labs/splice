// NEW: failure-handling slice 4. What ClientWire tells Claude Code about a failure in the R, P and T families, checked
// against what Claude Code 2.1.310 did with each answer, as the client reaction record holds it
// (tools/e2e/fixtures/client-reactions/2.1.310.toml, slice 0b). Each family promises the person something
// (final-design.md section 11): R is sent again by the client, P stops the turn for good, and T stops it so the client
// compacts. The families are this file's own reading of the design's table, never ClientWire's, and each verdict is
// read from the record, never from a run.
//
// R's promise holds before content: once model content reached the client it keeps what it holds, and the record shows
// no re-send after text (inband-*-after-content), which the design's R row leaves as the cause's own type.
// The login causes take the client's own login statuses (401 for its forwarded login, 403 for a login splice holds),
// whose reaction is the client's login flow, so they are checked for the status alone.
package splice.core.failure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.core.wire.HttpStatus
import java.io.File
import java.time.Instant

private const val RECORD = "tools/e2e/fixtures/client-reactions/2.1.310.toml"

/** The table each case of the record opens with. */
private const val CASE = "[[case]]"

private val LIFTS: List<Lift> = listOf(
    Lift.Unstated,
    Lift.At(Instant.parse("2026-10-26T18:00:00Z"), firm = true, source = LiftSource.RESET_HEADER),
    Lift.At(Instant.parse("2026-10-26T18:00:00Z"), firm = false, source = LiftSource.RETRY_AFTER),
)

private val BOOLS = listOf(false, true)

private val DECLINES: List<Decline> = listOf(
    Decline.TooLarge(bytes = 9_000_000L, limit = 8_000_000L),
    Decline.HeapBusy,
    Decline.Budget(limit = "$20.00", resetsAt = Instant.parse("2026-10-27T05:00:00Z")),
    Decline.BadBody(readBytes = 12L, declaredBytes = 40L),
    Decline.ReadTimeout,
    Decline.UnknownModel("not-served"),
    Decline.ForeignHost,
    Decline.BadLocalKey,
    Decline.LivenessProbe,
    Decline.NoSharedLogin,
    Decline.Unfit(weight = 212_992L, limit = 131_072L),
    Decline.SpliceKeyAsLogin(listOf("ANTHROPIC_AUTH_TOKEN")),
) + PreflightKind.entries.map { Decline.Preflight(it, windowTokens = 262_144L) } +
    RequestFeature.entries.map(Decline::Untranslatable)

private fun providerCauses(): List<FailureCause> = buildList {
    LIFTS.forEach { lift ->
        (LimitWindow.entries + null).forEach { add(FailureCause.Provider.Limited(lift, it)) }
        BOOLS.forEach { add(FailureCause.Provider.Overloaded(lift, loading = it)) }
        BOOLS.forEach { add(FailureCause.Provider.NeedsPayment(lift, subscription = it)) }
    }
    addAll(ServerFault.entries.map { FailureCause.Provider.ServerError(it) })
    add(FailureCause.Provider.Forbidden())
    addAll(LoginOwner.entries.map { FailureCause.Provider.AuthRejected(it) })
    BOOLS.forEach { add(FailureCause.Provider.TooLong(131_072L, whileAnswering = it)) }
    addAll(BOOLS.map { FailureCause.Provider.Rejected(staleReasoning = it) })
    add(FailureCause.Provider.ModelMissing("gone-model"))
    addAll(RefusedBy.entries.map { FailureCause.Provider.Refused(it) })
    addAll(BOOLS.map { FailureCause.Provider.Empty(reasoningOnly = it) })
    addAll(BOOLS.flatMap { runaway -> BOOLS.map { FailureCause.Provider.ToolTear(runaway, permanent = it) } })
    addAll(BOOLS.map { FailureCause.Provider.Unrecognized(clientError = it) })
}

private fun pathCauses(): List<FailureCause> = buildList {
    Unreach.entries.forEach { why -> BOOLS.forEach { add(FailureCause.Path.Unreachable(why, local = it)) } }
    Tear.entries.forEach { why -> BOOLS.forEach { add(FailureCause.Path.Torn(why, toolOpen = it)) } }
    Stall.entries.forEach { add(FailureCause.Path.Stalled(it)) }
}

private fun spliceCauses(): List<FailureCause> = buildList {
    add(FailureCause.Splice.NoLogin("claude login"))
    add(FailureCause.Splice.AtCapacity(limit = 4))
    DECLINES.forEach { add(FailureCause.Splice.Declined(it)) }
    BOOLS.forEach { add(FailureCause.Splice.TurnBudgetSpent(budgetMs = 900_000L, beforeFirstAttempt = it)) }
    add(FailureCause.Splice.CompactionUnfinished)
    StoppedBy.entries.forEach { add(FailureCause.Splice.Stopped(it)) }
    ProtocolFault.entries.forEach { add(FailureCause.Splice.Protocol(it)) }
    BOOLS.forEach { escaped -> BOOLS.forEach { add(FailureCause.Splice.Internal(escaped, permanent = it)) } }
}

/** Failure-handling slice 5: a hold that ended without the turn going on, for each way it ends and each cause it held. */
private fun heldCauses(): List<FailureCause> = buildList {
    val reset = Instant.parse("2026-10-26T18:00:00Z")
    val held = listOf(
        FailureCause.Provider.Limited(LIFTS[1], LimitWindow.FIVE_HOUR),
        FailureCause.Provider.Limited(Lift.Unstated, null),
        FailureCause.Provider.Overloaded(LIFTS[1], loading = false),
        FailureCause.Provider.NeedsPayment(Lift.Unstated, subscription = false),
    )
    Outlast.entries.forEach { why ->
        held.forEach { cause -> listOf(reset, null).forEach { add(FailureCause.Splice.HoldOutlasted(why, cause, it)) } }
    }
}

private val CAUSES: List<FailureCause> = providerCauses() + pathCauses() + spliceCauses() + heldCauses()

private val POSITIONS: List<Position> = Moment.entries.flatMap { moment -> BOOLS.map { Position(moment, it) } }

/** The families of section 11 this test checks, with the login causes apart: they are P, with a login status. */
private enum class Fam { R, P, LOGIN, T, L, H }

/** Section 11's table, as this file reads it. Null for a cause outside R, P and T: a held turn's ending (slice 5), a
 *  request that took too long to arrive (408, its own row), and a cut round, which never ends a turn. A limit and a
 *  spent account end through a hold step, which keeps today's R wire until slice 5. */
private object Section11 {
    fun of(cause: FailureCause): Fam? = when (cause) {
        is FailureCause.Provider -> provider(cause)
        is FailureCause.Path -> Fam.R
        is FailureCause.Splice -> splice(cause)
    }

    private fun provider(cause: FailureCause.Provider): Fam? = when (cause) {
        is FailureCause.Provider.TooLong -> Fam.T
        is FailureCause.Provider.AuthRejected -> Fam.LOGIN
        is FailureCause.Provider.Forbidden, is FailureCause.Provider.Rejected, is FailureCause.Provider.ModelMissing,
        is FailureCause.Provider.Refused,
        -> Fam.P
        is FailureCause.Provider.Unrecognized -> permanent(cause.clientError)
        is FailureCause.Provider.ReasoningCut -> null
        is FailureCause.Provider.Limited, is FailureCause.Provider.NeedsPayment, is FailureCause.Provider.Overloaded,
        is FailureCause.Provider.ServerError, is FailureCause.Provider.Empty, is FailureCause.Provider.ToolTear,
        -> Fam.R
    }

    private fun splice(cause: FailureCause.Splice): Fam? = when (cause) {
        is FailureCause.Splice.NoLogin -> Fam.LOGIN
        is FailureCause.Splice.Declined -> declined(cause.why)
        is FailureCause.Splice.Stopped -> permanent(cause.by == StoppedBy.CONSOLE)
        is FailureCause.Splice.Protocol -> permanent(cause.fault != ProtocolFault.CODE_MODE_START)
        is FailureCause.Splice.HoldOutlasted -> outlasted(cause)
        // A failure inside splice the same request earns again is permanent, whatever its words (V4-81's base_url).
        is FailureCause.Splice.Internal -> permanent(cause.permanent)
        is FailureCause.Splice.AtCapacity, is FailureCause.Splice.TurnBudgetSpent,
        FailureCause.Splice.CompactionUnfinished,
        -> Fam.R
    }

    private fun declined(why: Decline): Fam? = when (why) {
        Decline.HeapBusy -> Fam.R
        Decline.ReadTimeout -> null
        is Decline.Preflight -> Fam.T
        // The credential the session sent splice itself: a login cause, answered 401 so the client reads its key
        // helper again and sends the key it returns (http-401), which is how a rotated key strands no session.
        Decline.BadLocalKey, Decline.NotLaunched, is Decline.SpliceKeyAsLogin -> Fam.LOGIN
        is Decline.TooLarge, is Decline.Unfit, is Decline.Budget, is Decline.BadBody, is Decline.UnknownModel,
        Decline.ForeignHost, Decline.LivenessProbe, Decline.NoSharedLogin,
        is Decline.Untranslatable,
        -> Fam.P
    }

    private fun permanent(stops: Boolean): Fam = if (stops) Fam.P else Fam.R

    /** A hold that ended takes its held cause's family, and one the session's wall cut short is handed to the client. */
    private fun outlasted(cause: FailureCause.Splice.HoldOutlasted): Fam = when {
        cause.why == Outlast.WALL -> Fam.L
        cause.held is FailureCause.Provider.Limited -> Fam.H
        cause.held is FailureCause.Provider.NeedsPayment -> Fam.P
        else -> Fam.R
    }
}

/** One case of the record: its verdict on whether the client sent the request again, and how many times. */
private data class RecordCase(val id: String, val resent: String, val times: String?)

/** The record, read by line: core's tests carry no TOML reader, and the record is flat, [[case]] tables of string
 *  keys, each followed by [[case.evidence]] tables this reader skips. */
private object ReactionRecord {
    private val pair = Regex("""^(\w+) = "(.*)"$""")

    val cases: Map<String, RecordCase> by lazy { read(locate()) }

    private fun locate(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, RECORD) }
        .first { it.isFile }

    private fun read(file: File): Map<String, RecordCase> {
        val read = mutableListOf<MutableMap<String, String>>()
        var inCase = false
        file.readLines().map(String::trim).forEach { line ->
            if (line == CASE) read += mutableMapOf<String, String>()
            inCase = inside(line, inCase)
            val pair = if (inCase) pair.find(line) else null
            pair?.let { read.last()[it.groupValues[1]] = it.groupValues[2].replace("\\\"", "\"") }
        }
        return read.associate { it.getValue("id") to RecordCase(it.getValue("id"), it.getValue("resent"), it["times"]) }
    }

    /** Whether [line] and the lines after it belong to a case's own keys: a case's table opens them and its
     *  evidence's table closes them. */
    private fun inside(line: String, inCase: Boolean): Boolean = when (line) {
        CASE -> true
        "[[case.evidence]]" -> false
        else -> inCase
    }

    fun case(id: String): RecordCase? = cases[id]
}

/** Each status's rows in the record, by id: the one the client met with no x-should-retry false, and the one it met
 *  with that header. Null where the record holds no such row. The 529 rows turn on the position instead. */
private val HTTP_CASES: Map<Int, Pair<String?, String?>> = mapOf(
    HttpStatus.BAD_REQUEST to ("http-400-invalid-request" to "http-400-should-retry-false"),
    HttpStatus.UNAUTHORIZED to ("http-401" to "http-401"),
    HttpStatus.FORBIDDEN to ("http-403" to "http-403"),
    HttpStatus.NOT_FOUND to ("http-404" to "http-404"),
    HttpStatus.REQUEST_TIMEOUT to ("http-408" to "http-408"),
    HttpStatus.CONTENT_TOO_LARGE to ("http-413" to "http-413"),
    HttpStatus.TOO_MANY_REQUESTS to ("http-429-retry-after-30s" to "http-429-should-retry-false"),
    HttpStatus.SERVICE_UNAVAILABLE to (null to "http-503-should-retry-false"),
)

/** The record's case for an answer that is an HTTP status. */
private fun httpCase(answer: WireAnswer, at: Position): String? {
    val trueReset = !answer.recheck && answer.resetsAt != null
    return when {
        answer.status == HttpStatus.OVERLOADED ->
            if (at.moment == Moment.NON_STREAM) "http-529-non-stream" else "http-529-stream"
        // Family L with its true reset: the client sleeps until that reset and sends again (http-429-reset-hours-away).
        answer.handedOver && trueReset -> "http-429-reset-hours-away"
        else -> HTTP_CASES[answer.status]?.let { rows -> if (answer.shouldRetry == false) rows.second else rows.first }
    }
}

/** The record's name for an in-band error type, as its case ids spell it. */
private fun typeId(type: ErrorType): String =
    type.wireName.replace('_', '-').let { if (it == "api-error") it else it.removeSuffix("-error") }

/**
 * What the record says Claude Code did with [answer] given at [at]: true when it sent the request again, false when it
 * did not, and null when no case records that answer. In band, a committed stream carries splice's notice and no
 * content, the signed-notice case; once content reached the client it is the after-content case; and an error that
 * opens the stream comes before any block.
 */
private fun recordResent(answer: WireAnswer, at: Position): Boolean? {
    if (answer.status != null) return httpCase(answer, at)?.let(ReactionRecord::case)?.let(::verdict)
    val type = typeId(answer.type)
    return when (at.moment) {
        Moment.COMMITTED -> ReactionRecord.case("inband-signed-notice")?.let { timesFor(it, answer.type) }
        Moment.MID_CONTENT -> ReactionRecord.case("inband-$type-after-content")?.let(::verdict)
        Moment.ADMISSION, Moment.BEFORE_COMMIT, Moment.NON_STREAM ->
            ReactionRecord.case("inband-$type-before-content")?.let(::verdict)
    }
}

/** yes and no are verdicts; a mixed case answers only through its times, read per type. */
private fun verdict(case: RecordCase): Boolean? = when (case.resent) {
    "yes" -> true
    "no" -> false
    else -> null
}

/** A mixed case's times name each type: "1 for overloaded_error; none for invalid_request_error". */
private fun timesFor(case: RecordCase, type: ErrorType): Boolean? = case.times.orEmpty().split(';')
    .map { it.trim() }
    .firstOrNull { it.endsWith("for ${type.wireName}") }
    ?.let { !it.startsWith("none") }

private data class Answered(val cause: FailureCause, val at: Position, val repeated: Boolean) {
    val answer: WireAnswer = ClientWire.answer(cause, at, repeated)

    /** A repeated R ending is P (section 11). */
    val family: Fam? = Section11.of(cause)?.let { if (repeated && it == Fam.R) Fam.P else it }

    override fun toString(): String = "$cause at $at${if (repeated) ", repeated" else ""}: $answer"
}

private val ANSWERED: List<Answered> = CAUSES.flatMap { cause ->
    POSITIONS.flatMap { at -> BOOLS.map { Answered(cause, at, repeated = it) } }
}

private val LOGIN_STATUSES = setOf(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
private val LOGIN_TYPES = setOf(ErrorType.AUTHENTICATION, ErrorType.PERMISSION)

/** The record's case for an in-band answer given once model content reached the client, or null where the record
 *  holds none for its type. */
private fun afterContentCase(answer: WireAnswer): RecordCase? =
    ReactionRecord.case("inband-${typeId(answer.type)}-after-content")

/** Whether this answer breaks its family's promise, said in one line, or null when it keeps it. After content an
 *  answer keeps no promise the record cannot show: its type must be a row of the record, whatever that row says. */
private fun familyMiss(case: Answered): String? {
    val resent = recordResent(case.answer, case.at)
    val after = case.at.moment == Moment.MID_CONTENT
    val said = "(record: $resent): $case"
    return when (case.family) {
        Fam.R, Fam.L -> if (after || resent == true) null else "${case.family} not re-sent $said"
        Fam.P, Fam.T, Fam.H -> if (stopped(resent, after)) null else "${case.family} not stopped $said"
        Fam.LOGIN -> loginMiss(case)
        null -> null
    }
}

/** An R, P or T answer given in band after content whose type the record holds no row for, said in one line, or
 *  null. */
private fun unrecorded(case: Answered): String? {
    val inBandAfter = case.at.moment == Moment.MID_CONTENT && case.answer.status == null
    val needsRow = case.family in RECORDED && inBandAfter
    return if (needsRow && afterContentCase(case.answer) == null) {
        "${case.family} after content has no record row: $case"
    } else {
        null
    }
}

/** The families whose every answer must be a row of the record. */
private val RECORDED = setOf(Fam.R, Fam.P, Fam.T, Fam.L, Fam.H)

/** Whether a P or T answer stopped the turn. After content the record's own verdict is mixed for overloaded_error
 *  ("1 after thinking alone; none after text"), and the design leaves the type alone there, so the only breach after
 *  content is an answer the record says the client did send again. */
private fun stopped(resent: Boolean?, after: Boolean): Boolean = if (after) resent != true else resent == false

/** Whether a cause that is no login's takes a login's status or type, which would print a login label in front of a
 *  line that is not about one. */
private fun labelMiss(case: Answered): String? {
    if (case.family == Fam.LOGIN) return null
    val labelled = case.answer.status in LOGIN_STATUSES || case.answer.type in LOGIN_TYPES
    return if (labelled) "a non-login cause takes a login status: $case" else null
}

class ClientWireRecordTest {
    /** every status, type and x-should-retry ClientWire answers in the R, P and T families is a row in the 2.1.310
     *  record that does what its family promises: R is re-sent, P and T are not, and a non-login cause prints with no
     *  login label. */
    @Test
    fun `R, P and T answers are rows of the 2_1_310 record that keep their family's promise`() {
        assertTrue(ReactionRecord.cases.size >= 40, "the record was read: ${ReactionRecord.cases.keys}")
        val misses = ANSWERED.filter { it.family != null }
            .flatMap { listOfNotNull(unrecorded(it), familyMiss(it), labelMiss(it)) }
        assertEquals(emptyList<String>(), misses, "${misses.size} answers break their family's promise")
    }

    /** A line says Claude Code sends the turn again exactly where the record says the client does (SpliceLines). */
    @Test
    fun `clientResends says what the record says the client did`() {
        val misses = ANSWERED.filter { it.family != null && it.family != Fam.LOGIN }.mapNotNull { case ->
            recordResent(case.answer, case.at)?.let { resent ->
                if (case.answer.clientResends == resent) null else "clientResends misread, record $resent: $case"
            }
        }
        assertEquals(emptyList<String>(), misses, "${misses.size} answers misread")
    }

    /** Failure-handling slice 5: a limit the session cannot wait out is answered 429 with no x-should-retry, naming the
     *  true reset on a head with one login and a time RECHECK_SECONDS away on a pooled one, and the record says the
     *  client sleeps until that time and sends the turn again. A limit too far to wait for, or one that did not lift,
     *  stops: its answer is a row the record says the client does not send again. */
    @Test
    fun `the L answer is re-asked at the time it names, and the H answer is not re-sent`() {
        val beforeCommit = Position(Moment.BEFORE_COMMIT, streamBegan = false)
        val held = ANSWERED.map { it.cause }.filterIsInstance<FailureCause.Splice.HoldOutlasted>().distinct()
        val misses = held.flatMap { cause ->
            BOOLS.mapNotNull { otherLogin -> heldMiss(cause, beforeCommit, otherLogin) }
        }
        assertTrue(
            held.any { Section11.of(it) == Fam.L } && held.any { Section11.of(it) == Fam.H },
            "both families met",
        )
        assertEquals(emptyList<String>(), misses, "${misses.size} held endings break their promise")
    }

    private fun heldMiss(cause: FailureCause.Splice.HoldOutlasted, at: Position, otherLogin: Boolean): String? {
        val answer = ClientWire.answer(cause, at, otherLogin = otherLogin)
        val resent = recordResent(answer, at)
        val told = "$cause, other login $otherLogin: $answer (record: $resent)"
        return when (Section11.of(cause)) {
            Fam.L -> handedOverMiss(answer, cause, otherLogin, resent, told)
            Fam.H -> if (resent != false) "H re-sent: $told" else null
            Fam.R, Fam.P, Fam.T, Fam.LOGIN, null -> null
        }
    }

    private fun handedOverMiss(
        answer: WireAnswer,
        cause: FailureCause.Splice.HoldOutlasted,
        otherLogin: Boolean,
        resent: Boolean?,
        told: String,
    ): String? = when {
        answer.status != HttpStatus.TOO_MANY_REQUESTS || answer.shouldRetry == false ->
            "L is not a 429 the client retries: $told"
        resent != true -> "L not re-asked: $told"
        answer.resetsAt != cause.nextReset -> "L names another reset: $told"
        answer.recheck != otherLogin -> "L rechecks where no other login serves: $told"
        else -> null
    }

    /** A repeated R is the exception: where its own type is overloaded_error, which the client sends once more after
     *  thinking alone, it reads invalid_request_error, and the first case checks that it stops. */
    @Test
    fun `after content the type is the cause's own`() {
        ANSWERED.filter { it.at.moment == Moment.MID_CONTENT && it.family != null && !it.repeated }.forEach { case ->
            assertEquals(ClientWire.ownType(case.cause), case.answer.type, "$case")
            assertEquals(null, case.answer.status, "$case")
        }
    }
}

/** A login cause answers with a status while the status is splice's to choose: 401 for the client's own forwarded
 *  login and for the credential it sent splice itself, 403 for a login splice holds, each with a case in the record.
 *  x-should-retry false stops the client on the first two kinds; splice's door sends no such header, so the client
 *  re-reads its key helper on the 401 and a rotated key strands no session (http-401). */
private fun loginMiss(case: Answered): String? {
    val status = case.answer.status ?: return null
    val door = case.cause is FailureCause.Splice.Declined
    val client = (case.cause as? FailureCause.Provider.AuthRejected)?.owner == LoginOwner.CLIENT
    val expected = if (client || door) HttpStatus.UNAUTHORIZED else HttpStatus.FORBIDDEN
    val held = httpCase(case.answer, case.at)?.let(ReactionRecord::case) != null
    val retry = if (door) null else false
    val told = status == expected && case.answer.shouldRetry == retry
    return if (told && held) null else "login status: $case"
}
