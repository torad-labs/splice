// NEW: server mode (docs/specs/server-mode.md, F8). ResourceStamp for an OTLP export request in protobuf. The request is
// read field by field, with no schema compiled in: every signal's request lists its resource entries under field 1,
// each entry holds its Resource under field 1, and a Resource its attributes (KeyValue: key 1, AnyValue 2) under field
// 1. Only those are rewritten; every other field, the records included, is carried through as its bytes came.
package splice.server.telemetry

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.UnknownFieldSet

internal class ProtoStamp {
    /** [body], a protobuf export request, with [stamp] on each resource; null when it is no protobuf message. */
    fun stamped(body: ByteArray, stamp: ResourceStamp): ByteArray? = try {
        val request = UnknownFieldSet.parseFrom(body)
        if (!request.hasField(LISTED)) {
            body
        } else {
            val entries = request.getField(LISTED).lengthDelimitedList.map { stampedEntry(it, stamp) }
            replaced(request, LISTED, entries).toByteArray()
        }
    } catch (_: InvalidProtocolBufferException) {
        null
    }

    /** One resource entry, its Resource stamped; an entry with no Resource gets one holding the stamp alone. */
    private fun stampedEntry(entry: ByteString, stamp: ResourceStamp): ByteString {
        val fields = UnknownFieldSet.parseFrom(entry)
        // Protobuf merges an embedded message that occurs more than once, so every occurrence is read as one.
        val resource = UnknownFieldSet.parseFrom(ByteString.copyFrom(fields.getField(LISTED).lengthDelimitedList))
        val kept = resource.getField(LISTED).lengthDelimitedList.filterNot { keyOf(it) in stamp.owned }
        val written = stamp.written.map { (key, value) -> keyValue(key, value) }
        val stamped = replaced(resource, LISTED, kept + written).toByteString()
        return replaced(fields, LISTED, listOf(stamped)).toByteString()
    }

    /** The key of an attribute, a KeyValue's field 1; null for one that is no message. */
    private fun keyOf(attribute: ByteString): String? = try {
        UnknownFieldSet.parseFrom(attribute).getField(LISTED).lengthDelimitedList.lastOrNull()?.toStringUtf8()
    } catch (_: InvalidProtocolBufferException) {
        null
    }

    /** A KeyValue of [key] and the string [value] (AnyValue's string_value, field 1). */
    private fun keyValue(key: String, value: String): ByteString {
        val string = bytes(listOf(ByteString.copyFromUtf8(value)))
        val any = UnknownFieldSet.newBuilder().addField(LISTED, string).build()
        return UnknownFieldSet.newBuilder()
            .addField(LISTED, bytes(listOf(ByteString.copyFromUtf8(key))))
            .addField(KEY_VALUE_FIELD, bytes(listOf(any.toByteString())))
            .build()
            .toByteString()
    }

    /** [message] with field [number] holding [values] alone, as length-delimited occurrences. */
    private fun replaced(message: UnknownFieldSet, number: Int, values: List<ByteString>): UnknownFieldSet =
        UnknownFieldSet.newBuilder(message).addField(number, bytes(values)).build()

    private fun bytes(values: List<ByteString>): UnknownFieldSet.Field =
        UnknownFieldSet.Field.newBuilder().apply { values.forEach { addLengthDelimited(it) } }.build()
}

// The field each level lists what the stamp reads under: a request's resource entries, an entry's Resource, a
// Resource's attributes, a KeyValue's key, an AnyValue's string.
private const val LISTED = 1

// why: the field number OTLP gives a KeyValue's value, an AnyValue.
private const val KEY_VALUE_FIELD = 2
