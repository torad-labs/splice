package splice.app.mods.handover

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import splice.app.mods.sessions.ModSessionEntry

// why: new processes must also boot if the predecessor was killed or did not support this protocol.
private const val CONFIRMATION_MS = 15_000L

internal class ModHandoverStarts(private val expected: Boolean) {
    private val received = mutableListOf<ModSessionEntry>()
    private val confirmed = CompletableDeferred<List<ModSessionEntry>>()

    @Synchronized
    fun accept(batch: ModHandoverBatch) {
        if (!expected || confirmed.isCompleted) return
        received.addAll(batch.starts)
        if (batch.startsComplete) confirmed.complete(received.toList())
    }

    suspend fun await(): List<ModSessionEntry> =
        if (expected) withTimeoutOrNull(CONFIRMATION_MS) { confirmed.await() } ?: emptyList() else emptyList()
}
