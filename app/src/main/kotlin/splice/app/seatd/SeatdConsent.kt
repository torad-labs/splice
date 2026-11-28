package splice.app.seatd

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.app.desktop.record.StateRecords
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.topology.TopologyStatePaths
import java.io.IOException

@Serializable
internal enum class SeatdChoice {
    @SerialName("opted_in")
    OPTED_IN,

    @SerialName("declined")
    DECLINED,
}

/** A download decision belongs to this installation, not to splice.toml or the Desktop mode switch. */
internal class SeatdConsent(env: EnvReader) {
    private val file = TopologyStatePaths(env).current().stateDir.resolve("seatd.json")
    private val record = StateRecords(file, SeatdDecision.serializer(), SeatdDecision(), "seatd", LogSink { })

    fun choice(): SeatdChoice? = record.get().choice

    fun save(choice: SeatdChoice): String? = try {
        record.update { SeatdDecision(choice) }
        null
    } catch (_: IOException) {
        "Could not save your seatd-pipe choice at $file. Make the state folder writable, then run " +
            "`splice setup --update` to record a choice if the companion is still missing."
    }
}

@Serializable
private data class SeatdDecision(val choice: SeatdChoice? = null)
