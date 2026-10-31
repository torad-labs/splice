package splice.codemode.nativehost.grpc

import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import java.io.IOException

/** Checked accessors for the pinned protocol; omitted messages and unknown enum values are protocol errors. */
internal object NativeGrpcFields {
    fun text(message: DynamicMessage, name: String): String =
        NativeGrpcWire.value(message, name) as? String ?: throw IOException("Missing CodeMode field $name")

    fun identifier(message: DynamicMessage, name: String): String {
        val value = text(message, name)
        val label = name.removeSuffix("_id").replace('_', ' ') + " ID"
        if (value.isEmpty()) throw NativeGrpcProtocolException("gRPC code-mode host returned an empty $label")
        if (value.toByteArray(Charsets.UTF_8).size > MAX_IDENTIFIER_BYTES) {
            throw NativeGrpcProtocolException(
                "gRPC code-mode host returned $label exceeding $MAX_IDENTIFIER_BYTES bytes",
            )
        }
        return value
    }

    fun message(message: DynamicMessage, name: String): DynamicMessage =
        NativeGrpcWire.value(message, name) as? DynamicMessage ?: throw NativeGrpcProtocolException(
            when (name) {
                "started" -> "gRPC code-mode execution omitted its starting event"
                "outcome" -> "code-mode execution omitted its outcome"
                "missing_cell" -> "code-mode wait omitted its outcome"
                else -> "Missing CodeMode message $name"
            },
        )

    fun optional(message: DynamicMessage, name: String): DynamicMessage? =
        NativeGrpcWire.value(message, name) as? DynamicMessage

    fun bytes(message: DynamicMessage, name: String): ByteString? = NativeGrpcWire.value(message, name) as? ByteString

    fun unsigned(message: DynamicMessage, name: String): ULong =
        (NativeGrpcWire.value(message, name) as? Long)?.toULong() ?: throw IOException("Missing CodeMode uint64 $name")

    fun enumeration(message: DynamicMessage, name: String): Descriptors.EnumValueDescriptor =
        NativeGrpcWire.value(message, name) as? Descriptors.EnumValueDescriptor
            ?: throw IOException("Missing CodeMode enum $name")

    fun messages(message: DynamicMessage, name: String): List<DynamicMessage> {
        val values = NativeGrpcWire.value(message, name) as? List<*> ?: throw IOException("Missing CodeMode list $name")
        return values.map { it as? DynamicMessage ?: throw IOException("Invalid CodeMode list element $name") }
    }
}

// The official gRPC adapter accepts nonempty identifiers of at most 256 UTF-8 bytes.
private const val MAX_IDENTIFIER_BYTES = 256
