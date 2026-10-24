package splice.provider.codex.diagnostics

/** One bridge and request history shared by the independent callback diagnostic scenarios. */
internal class CodeModeGatewayBridgeProbeState(
    val fixture: CodeModeGatewayProbeSupport,
    val parallelCallbacks: Boolean,
) {
    val requests = CodeModeGatewayBridgeRequests(fixture)
    val acceptedResult = "accepted-client-result-${fixture.nonce}"
    val session = CodeModeGatewayBridgeSession(fixture, requests, parallelCallbacks)
    val manager = session.bridge()

    fun close() = manager.onProviderStop()
}
