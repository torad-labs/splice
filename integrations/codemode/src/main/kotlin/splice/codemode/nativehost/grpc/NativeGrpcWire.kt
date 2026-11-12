package splice.codemode.nativehost.grpc

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import io.grpc.MethodDescriptor
import java.io.InputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

private const val NATIVE_GRPC_DESCRIPTOR_SHA: String =
    "9a139e1d6a48900cd117dc0510be96bc003f72fde29e7dff9ba1db8a92aaab09"

/** Uses the exact official 0.164.0 schema, including proto3 optional-field presence. */
internal object NativeGrpcWire {
    private val descriptor: Descriptors.FileDescriptor by lazy {
        val resource = "/splice/codemode/native/grpc/codex.code_mode.v1.desc"
        val bytes = requireNotNull(javaClass.getResourceAsStream(resource)) { "Missing native gRPC schema" }
            .use(InputStream::readAllBytes)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        check(digest == NATIVE_GRPC_DESCRIPTOR_SHA) { "Native gRPC schema identity mismatch" }
        val file = DescriptorProtos.FileDescriptorSet.parseFrom(bytes).fileList.single()
        check(file.`package` == "codex.code_mode.v1") { "Native gRPC schema package mismatch" }
        Descriptors.FileDescriptor.buildFrom(file, emptyArray())
    }
    private val methods = ConcurrentHashMap<String, MethodDescriptor<DynamicMessage, DynamicMessage>>()

    fun message(type: String, fields: Map<String, Any?> = emptyMap()): DynamicMessage {
        val schema = requireNotNull(descriptor.findMessageTypeByName(type)) { "Unknown native gRPC message: $type" }
        val builder = DynamicMessage.newBuilder(schema)
        fields.forEach { (name, value) ->
            if (value == null) return@forEach
            val field = requireNotNull(schema.findFieldByName(name)) { "Unknown native gRPC field: $type.$name" }
            assign(builder, field, value)
        }
        return builder.build()
    }

    /** Absent optional fields return null; ordinary scalar defaults remain visible. */
    fun value(message: DynamicMessage, field: String): Any? {
        val schema =
            requireNotNull(message.descriptorForType.findFieldByName(field)) { "Unknown native gRPC field: $field" }
        return if (schema.hasPresence() && !message.hasField(schema)) null else message.getField(schema)
    }

    fun method(name: String): MethodDescriptor<DynamicMessage, DynamicMessage> = methods.computeIfAbsent(name) {
        val service = requireNotNull(descriptor.findServiceByName("CodeModeHost"))
        val rpc = requireNotNull(service.findMethodByName(name)) { "Unknown native gRPC method: $name" }
        check(!rpc.isClientStreaming) { "Unexpected client-streaming native gRPC method" }
        val type = if (rpc.isServerStreaming) {
            MethodDescriptor.MethodType.SERVER_STREAMING
        } else {
            MethodDescriptor.MethodType.UNARY
        }
        MethodDescriptor.newBuilder<DynamicMessage, DynamicMessage>()
            .setFullMethodName(MethodDescriptor.generateFullMethodName(service.fullName, name))
            .setType(type)
            .setRequestMarshaller(marshaller(rpc.inputType))
            .setResponseMarshaller(marshaller(rpc.outputType))
            .build()
    }

    private fun <T : Any> assign(
        builder: DynamicMessage.Builder,
        field: Descriptors.FieldDescriptor,
        value: T,
    ) {
        if (field.isRepeated) {
            val values = value as? List<*> ?: error("Invalid native gRPC repeated field: ${field.fullName}")
            values.forEach { builder.addRepeatedField(field, NativeGrpcValues.scalar(field, requireNotNull(it))) }
        } else {
            builder.setField(field, NativeGrpcValues.scalar(field, value))
        }
    }

    private fun marshaller(schema: Descriptors.Descriptor): MethodDescriptor.Marshaller<DynamicMessage> =
        object : MethodDescriptor.Marshaller<DynamicMessage> {
            override fun stream(value: DynamicMessage): InputStream = value.toByteString().newInput()
            override fun parse(stream: InputStream): DynamicMessage = DynamicMessage.parseFrom(schema, stream)
        }
}
