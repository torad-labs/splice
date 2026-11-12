package splice.provider.codex

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertTrue
import splice.provider.codex.diagnostics.CodeModeGatewayBridgeSink
import splice.provider.codex.diagnostics.CodeModeGatewayProbeSupport
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.upstream.codemode.NativeCodeModeNotification
import splice.upstream.sse.WireSink
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours

/** A real retained record and store; no script engine or client tool is opened by notification delivery. */
internal class CodeModeNotificationFixture(directory: Path) : AutoCloseable {
    private val fixture = CodeModeGatewayProbeSupport("a".repeat(32), directory)
    val config = CodeModeBridgeConfig(
        { error("Notification delivery must not open a runtime or execute a client tool") },
        fixture.location,
        clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC),
    )
    val registry = CodexCodeModeRegistry(config, Json, 1.hours)
    val firstSink = CodeModeGatewayBridgeSink()
    val first = context(firstSink)
    val conversation = CodeModeTurnIdentity().conversationId(first.turn)
    val record = fixture.record().also {
        it.sessionId = first.turn.sessionId
        it.conversationId = conversation
        it.nativeCellId = "cell-1"
        it.nativeRuntime = true
    }

    init {
        assertTrue(registry.add(record))
    }

    fun context(sink: WireSink): CodeModeRunContext = fixture.context(fixture.initial).let {
        it.copy(link = it.link.copy(sink = sink))
    }

    fun notification(text: String): NativeCodeModeNotification = NativeCodeModeNotification(
        conversation,
        "cell-1",
        record.origin.outerCallId,
        text,
    )

    override fun close() {
        registry.timed.finish { registry.onHeadStop() }
    }
}
