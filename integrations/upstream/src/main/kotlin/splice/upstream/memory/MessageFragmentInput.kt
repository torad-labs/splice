package splice.upstream.memory

import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapJson
import splice.core.wire.AnthropicMessage
import splice.core.wire.MessageFragmentInputs

internal sealed interface MessageFragmentInput {
    val fingerprint: Int
    val bytes: Long
    fun same(other: MessageFragmentInput): Boolean
    fun freeze(): MessageFragmentInput
}

internal class TypedFragmentInput(private val message: AnthropicMessage) : MessageFragmentInput {
    override val fingerprint: Int get() = message.hashCode()
    override val bytes: Long get() = MessageFragmentInputs.bytes(message)
    override fun same(other: MessageFragmentInput): Boolean =
        other is TypedFragmentInput && MessageFragmentInputs.same(message, other.message)
    override fun freeze(): MessageFragmentInput = TypedFragmentInput(MessageFragmentInputs.freeze(message))
}

internal class RawFragmentInput(private val message: JsonObject) : MessageFragmentInput {
    override val fingerprint: Int get() = message.hashCode()
    override val bytes: Long get() = HeapJson.bytes(message)
    override fun same(other: MessageFragmentInput): Boolean =
        other is RawFragmentInput && MessageFragmentInputs.same(message, other.message)
    override fun freeze(): MessageFragmentInput =
        RawFragmentInput(MessageFragmentInputs.freeze(message) as JsonObject)
}
