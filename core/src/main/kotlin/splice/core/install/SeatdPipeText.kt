package splice.core.install

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import java.io.IOException

/** The reviewed release data, bundled unchanged from the release tool's input. */
public val seatdPipePinDocument: JsonObject? by lazy { SeatdPinResource.read() }

public val seatdPipeRepository: String by lazy {
    JsonScalars.str(seatdPipePinDocument, "repository").orEmpty()
}

public const val SEATD_PIPE_EXPLANATION: String =
    "seatd-pipe is the optional open-source session and pipe companion for Claude Code in Desktop gateway mode, " +
        "live model switching across providers, and Gemini/Antigravity support."

private object SeatdPinResource {
    fun read(): JsonObject? = try {
        javaClass.getResourceAsStream("/seatd-pipe.json")?.bufferedReader()?.use {
            Json.parseToJsonElement(it.readText()) as? JsonObject
        }
    } catch (_: SerializationException) {
        null
    } catch (_: IOException) {
        null
    }
}
