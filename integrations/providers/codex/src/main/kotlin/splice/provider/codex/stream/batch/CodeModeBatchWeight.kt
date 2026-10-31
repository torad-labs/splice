package splice.provider.codex.stream.batch

import splice.provider.codex.state.CodeModeWeight

/** Queue metadata and the response vector are charged independently of execution source and native cell leases. */
internal object CodeModeBatchWeight {
    fun bytes(batch: CodeModeNativeBatch?): Long = batch?.let {
        BATCH_METADATA_BYTES + CodeModeWeight.STORED.text(
            it.ownerId,
        ) + it.items.orEmpty().sumOf(CodeModeWeight.STORED::json)
    } ?: 0L
}

// Header objects, list references and primitive dispatch/order fields retained by live and immutable snapshots.
private const val BATCH_METADATA_BYTES = 256L
