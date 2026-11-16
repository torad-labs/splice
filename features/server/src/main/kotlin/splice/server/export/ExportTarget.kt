// NEW: server mode (docs/specs/server-mode.md, F5, ledger-export). Where `[server.export]` copies the ledger: a folder
// (`file:///dir`) or an S3-compatible bucket (`s3://bucket/prefix`). Each takes one hour's file whole, by its key under
// the target (`splice-usage/YYYY/MM/DD/HH.jsonl`), and replaces whatever that key held, so an hour written again holds
// each of its rows once.
package splice.server.export

/** One place an hour's file is written to. */
internal fun interface ExportTarget {
    /** Writes [bytes] as [key], replacing it; null when written, else why not, in words an operator can act on. */
    suspend fun put(key: String, bytes: ByteArray): String?
}
