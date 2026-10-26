package splice.codemode.nativehost.sessions

import splice.codemode.nativehost.NativeSessionBinding

/** Closing a logical owner cannot publish a replacement under the same expired owner. */
internal class NativeLogicalSessions(
    private val connection: NativeSessionConnection,
    private val heapBytes: Long,
    private val maxYieldTimeMs: Long?,
) : AutoCloseable {
    private val owners = mutableMapOf<String, NativeLogicalSession>()
    private var closed = false

    @Synchronized
    fun session(key: String): NativeLogicalSession {
        check(!closed) { "Native code-mode runtime is closed" }
        return owners.getOrPut(key) { NativeLogicalSession(connection, heapBytes, maxYieldTimeMs) }
    }

    @Synchronized
    fun remove(key: String): NativeSessionBinding? = owners.remove(key)?.close()

    @Synchronized
    override fun close() {
        closed = true
        owners.values.forEach { it.close() }
        owners.clear()
    }
}
