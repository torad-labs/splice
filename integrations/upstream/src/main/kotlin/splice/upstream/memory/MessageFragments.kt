package splice.upstream.memory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One independently mapped message, without request-scoped side effects. */
public fun interface MessageFragmentBuild {
    public operator fun invoke(): JsonArray
}

/** Builds one independently scrubbed raw message, without account/header state.
 *  Distinct from GlobalMcpServers: transforms the captured message, never reads global MCP configuration.
 */
public fun interface RawMessageFragmentBuild {
    public operator fun invoke(): JsonObject
}

/** A builder-private transformation namespace over one process-bounded store.
 *  No auth/header/account state, whole raw request, slot lease or reasoning lookup is cached.
 */
public class MessageFragments {
    private val namespace = FragmentNamespace()

    public fun message(
        context: String,
        input: splice.core.wire.AnthropicMessage,
        build: MessageFragmentBuild,
    ): JsonArray {
        val source = TypedFragmentInput(input)
        val key = FragmentKey(namespace, context, source.fingerprint)
        MessageFragmentStore.find(key, source)?.let { return it as JsonArray }
        return MessageFragmentStore.save(key, source, build()) as JsonArray
    }

    public fun rawMessage(context: String, input: JsonObject, build: RawMessageFragmentBuild): JsonObject {
        val source = RawFragmentInput(input)
        val key = FragmentKey(namespace, context, source.fingerprint)
        MessageFragmentStore.find(key, source)?.let { return it as JsonObject }
        return MessageFragmentStore.save(key, source, build()) as JsonObject
    }
}

internal class FragmentNamespace
internal data class FragmentKey(val namespace: FragmentNamespace, val context: String, val fingerprint: Int)
