// NEW: server mode (docs/specs/server-mode.md, F3 and F5). An amount of dollars as an operator writes it in
// splice.toml: `person_daily_usd = 50` as readily as `person_daily_usd = 50.0`. TOML keeps integers and floats apart,
// and ktoml refuses an integer where a Double is declared, so a whole amount would fail boot as an unreadable line.
package splice.core.topology

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

internal object TomlDollars : KSerializer<Double> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("splice.TomlDollars", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double = try {
        decoder.decodeDouble()
    } catch (_: SerializationException) {
        // A whole number: ktoml reads the same key again as the integer it is.
        decoder.decodeLong().toDouble()
    }

    override fun serialize(encoder: Encoder, value: Double) {
        encoder.encodeDouble(value)
    }
}
