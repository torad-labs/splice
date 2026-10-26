package splice.codemode.nativehost.grpc.state

import java.util.UUID

// why: Codex rust-v0.164.0 code-mode/src/grpc_session/state.rs:15 sets MAX_RECENT_CALLBACK_IDS = 4_096.
private const val NATIVE_GRPC_RECENT_IDS: Int = 4_096

/** Matches the per-session recent callback ledgers in the official gRPC client. */
internal class NativeGrpcRecentIds {
    private val values = HashSet<UUID>()
    private val order = ArrayDeque<UUID>()

    fun remember(value: UUID) {
        if (values.add(value)) {
            order.addLast(value)
            while (order.size > NATIVE_GRPC_RECENT_IDS) values.remove(order.removeFirst())
        }
    }

    fun contains(value: UUID): Boolean = value in values
    fun remove(value: UUID): Boolean = values.remove(value)
}
