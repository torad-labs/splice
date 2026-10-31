package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage

internal object NativeGrpcToolNames {
    fun key(name: DynamicMessage): Pair<String, String> =
        ((NativeGrpcWire.value(name, "namespace") as? String)?.takeIf(String::isNotEmpty) ?: "functions") to
            NativeGrpcFields.text(name, "name")

    fun display(name: DynamicMessage): String {
        val (namespace, member) = key(name)
        return if (namespace == "functions") member else namespace + member
    }
}
