// PORT-OF: WsUpstream.kt @ 81ff23c — invariants: logKey stays a short stable digest, never the raw
// key, in every log line this transport writes.
package splice.dialect.responses

/** Enough of an upstream error message to diagnose from daemon.log without unbounded growth. */
internal const val ERR_SNIPPET = 160

/** The operator-facing key form. The connection key deliberately concatenates the CHAIN key
 *  (the client's session id + conversation identity — raw client-derived text) with the header
 *  digest, and six log sites here interpolated it verbatim into daemon.log while the runner's
 *  own logKey existed for exactly this reason (review of #72, the one finding of it that the
 *  header-digest fix did not finish). Same short stable digest as the runner's: enough to
 *  correlate connect/busy/kill lines for one connection, nothing recoverable. */
internal class WsLogKeys {
    internal fun logKey(key: String): String = "ws-" + Integer.toHexString(key.hashCode())
}
