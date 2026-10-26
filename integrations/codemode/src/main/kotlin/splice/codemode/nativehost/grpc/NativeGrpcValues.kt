package splice.codemode.nativehost.grpc

import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors

/** Convert reflection inputs without erasing unsigned integer bits or protobuf enum identities. */
internal object NativeGrpcValues {
    fun <T : Any> scalar(field: Descriptors.FieldDescriptor, value: T): Any = when (field.javaType) {
        Descriptors.FieldDescriptor.JavaType.ENUM -> enumeration(field, value)
        Descriptors.FieldDescriptor.JavaType.BYTE_STRING -> if (value is ByteArray) {
            ByteString.copyFrom(
                value,
            )
        } else {
            value
        }
        Descriptors.FieldDescriptor.JavaType.LONG -> long(field, value)
        Descriptors.FieldDescriptor.JavaType.INT, Descriptors.FieldDescriptor.JavaType.DOUBLE,
        Descriptors.FieldDescriptor.JavaType.FLOAT,
        -> number(field, value)
        Descriptors.FieldDescriptor.JavaType.BOOLEAN, Descriptors.FieldDescriptor.JavaType.STRING,
        Descriptors.FieldDescriptor.JavaType.MESSAGE,
        -> value
    }

    private fun <T : Any> enumeration(field: Descriptors.FieldDescriptor, value: T): Descriptors.EnumValueDescriptor =
        when (value) {
            is Descriptors.EnumValueDescriptor -> value
            is String -> requireNotNull(field.enumType.findValueByName(value)) {
                "Unknown native gRPC enum value: $value"
            }
            is Number -> requireNotNull(field.enumType.findValueByNumber(value.toInt()))
            else -> error("Invalid native gRPC enum value")
        }

    private fun <T : Any> long(field: Descriptors.FieldDescriptor, value: T): Long = when (value) {
        is ULong -> value.toLong()
        is Number -> value.toLong()
        else -> error("Invalid native gRPC integer field: ${field.fullName}")
    }

    private fun <T : Any> number(field: Descriptors.FieldDescriptor, value: T): Number {
        val number = value as? Number ?: error("Invalid native gRPC numeric field: ${field.fullName}")
        return when (field.javaType) {
            Descriptors.FieldDescriptor.JavaType.INT -> number.toInt()
            Descriptors.FieldDescriptor.JavaType.DOUBLE -> number.toDouble()
            Descriptors.FieldDescriptor.JavaType.FLOAT -> number.toFloat()
            else -> error("Invalid native gRPC numeric field: ${field.fullName}")
        }
    }
}
