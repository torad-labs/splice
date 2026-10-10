// NEW: validated streaming perf facts; unsupported shapes use the existing tree parser.
package splice.app.sources

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.util.JsonRecyclerPools
import splice.core.perf.PerfKeys
import splice.core.perf.PerfTurnIds
import splice.core.util.Cancellables
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfTranscriptLink
import splice.usage.perf.PerfTurnFacts

// why: 64 fields cover the synthetic 49-field shape with headroom and bound retained duplicate-name scratch.
private const val PERF_STREAM_FIELD_LIMIT = 64

// why: 4 Ki characters cover the measured 1 KiB row with headroom and cap the source-owned recycler buffer.
private const val PERF_STREAM_LINE_CHARS = 4_096

/** Every canonicalized name belongs to the source's charged pool; a refused name discards the factory table. */
internal class PerfRowDecode(private val pool: PerfFieldNames) {
    private val recycler = JsonRecyclerPools.newBoundedPool(1)
    private var factory = JsonFactoryBuilder().disable(JsonFactory.Feature.INTERN_FIELD_NAMES)
        .recyclerPool(recycler).build()
    private val seen = ArrayList<String>()
    private val numbers = PerfNumericBuilder(pool)
    private var unsharedNames = false

    fun decode(line: String): Result<PerfDecodedRow> = Cancellables.runCatchingCancellable {
        var completed = false
        try {
            require(line.length <= PERF_STREAM_LINE_CHARS) { "large perf row requires the tree reader" }
            val decoded = factory.createParser(line).use { parser ->
                require(parser.nextToken() == JsonToken.START_OBJECT) { "perf row is not an object" }
                val headers = Headers()
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    val key = requireNotNull(parser.currentName())
                    val shared = pool.share(key)
                    if (shared !== key || !pool.contains(key)) unsharedNames = true
                    require(seen.size < PERF_STREAM_FIELD_LIMIT) { "wide perf row requires the tree reader" }
                    require(!seen.contains(key)) { "duplicate perf field requires the tree reader" }
                    seen.add(key)
                    val token = requireNotNull(parser.nextToken())
                    require(token.isScalarValue) { "nested perf input requires the tree reader" }
                    require(token != JsonToken.VALUE_NUMBER_FLOAT) { "noninteger lexeme requires the tree reader" }
                    if (token == JsonToken.VALUE_NUMBER_INT) numbers.put(key, parser.longValue)
                    headers.accept(key, parser)
                }
                require(parser.currentToken() == JsonToken.END_OBJECT) { "unterminated perf row" }
                require(parser.nextToken() == null) { "trailing perf input" }
                val fields = PerfNumericFields(numbers)
                PerfDecodedRow(headers.row(fields), fields.retainedBytes, headers.drops)
            }
            completed = true
            decoded
        } finally {
            // nextToken can canonicalize a name and fail on its value before the name reaches our charged pool.
            if (unsharedNames || !completed) {
                factory = JsonFactoryBuilder().disable(JsonFactory.Feature.INTERN_FIELD_NAMES)
                    .recyclerPool(recycler).build()
            }
            unsharedNames = false
            seen.clear()
            numbers.clear()
        }
    }

    private class Headers {
        private var outcome: String? = null
        private var cause: String? = null
        private var model: String? = null
        private var session: String? = null
        private var sessionId: String? = null
        private var response: String? = null
        private var account: String? = null
        private var turn: String? = null
        private var turnId: String? = null
        private var cacheCold: Boolean? = null
        private var compact: Boolean? = null
        private var captured: Boolean? = null
        private var providerMessage: String? = null
        var drops: Long? = null
            private set

        fun accept(key: String, parser: JsonParser) {
            when (key) {
                "outcome" -> outcome = text(parser)
                "model" -> model = text(parser)
                "session" -> session = text(parser)
                "session_id" -> sessionId = text(parser)
                "response_message_id" -> response = text(parser)
                "account" -> account = text(parser)
                "turn" -> turn = text(parser)
                else -> marker(key, parser)
            }
        }

        private fun marker(key: String, parser: JsonParser) {
            when (key) {
                "cause" -> cause = text(parser)
                "turn_id" -> turnId = text(parser)
                "cache_cold" -> cacheCold = text(parser)?.toBooleanStrictOrNull()
                "compact" -> compact = text(parser)?.toBooleanStrictOrNull()
                // V4-444: the capture flag and the upstream's own failure words, read like the facts above them.
                "capture" -> captured = text(parser)?.toBooleanStrictOrNull()
                "provider_message" -> providerMessage = text(parser)
                PerfKeys.ASYNC_IO_DROPS -> drops = text(parser)?.toLongOrNull()
            }
        }

        fun row(fields: PerfNumericFields): PerfRow {
            // The single legacy-probe fingerprint stays in LivenessProbe, reached through the tree fallback.
            require(model != "") { "empty model requires legacy probe classification" }
            return PerfRow(
                ts = requireNotNull(fields["ts"]) { "missing numeric perf timestamp" },
                outcome = outcome ?: "?",
                cause = cause,
                fields = fields,
                facts = PerfTurnFacts(
                    model = model,
                    session = session,
                    account = account,
                    cacheCold = cacheCold,
                    compact = compact,
                    captured = captured,
                    providerMessage = providerMessage,
                ),
                transcript = PerfTranscriptLink(sessionId = sessionId, responseMessageId = response),
                turns = PerfTurnIds(trace = turn, request = turnId),
            )
        }

        private fun text(parser: JsonParser): String? {
            val token = parser.currentToken()
            if (token == JsonToken.VALUE_NULL || !token.isScalarValue) return null
            return parser.text.takeUnless { '�' in it }
        }
    }
}

internal data class PerfDecodedRow(val row: PerfRow, val numericBytes: Long, val drops: Long?)
