package splice.app.diagnostics

import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import splice.codemode.NativeCodeModeRuntime
import splice.codemode.WorkerSpawn
import splice.core.util.JsonWire
import splice.provider.codex.CodeModeGatewayNativeProbe
import splice.provider.codex.CodeModeGatewayProbe
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture

/** Model-free diagnostic entry point exercising the production runtime and its bundled host. */
public object CodeModeParityProbe {
    @JvmStatic
    public fun main(args: Array<String>) {
        val scope = LifecycleScope(ProcessDispatchers().io())
        val done = CompletableFuture<Unit>()
        val job = scope.launch { run(args) }
        job.invokeOnCompletion { error ->
            if (error == null) done.complete(Unit) else done.completeExceptionally(error)
        }
        try {
            done.get()
        } finally {
            scope.cancel()
        }
    }

    private suspend fun run(args: Array<String>) {
        if (args.firstOrNull() == "--native-gateway-fixture") {
            nativeGateway(args)
            return
        }
        if (args.firstOrNull() == "--format-fixture") {
            require(args.size == 2) { "Expected a formatter probe nonce" }
            CodeModeParityFormat.run(args[1])
            return
        }
        if (args.firstOrNull() == "--gateway-fixture") {
            require(args.size == GATEWAY_PROBE_ARGUMENTS) { "Expected gateway case, nonce, state directory and phase" }
            val details = args.drop(1)
            CodeModeParityText.write(
                System.out,
                JsonWire.string(CodeModeGatewayProbe.run(details[0], details[1], Path.of(details[2]), details.last())),
            )
            return
        }
        require(args.size == 1) { "Expected the reference identity of the candidate native host" }
        runtime(Path.of(args.single())).use { runtime ->
            coroutineScope { CodeModeParityProtocol(runtime, CodeModeParityFrames(), this).run() }
        }
    }

    private suspend fun nativeGateway(args: Array<String>) {
        require(args.size == GATEWAY_PROBE_ARGUMENTS) { "Expected native gateway case, nonce, state and host identity" }
        val details = args.drop(1)
        runtime(Path.of(details.last())).use { native ->
            val result = CodeModeGatewayNativeProbe.run(details[0], details[1], Path.of(details[2]), native)
            CodeModeParityText.write(System.out, JsonWire.string(result))
        }
    }

    private fun runtime(referenceHost: Path): NativeCodeModeRuntime {
        val expected = digest(referenceHost)
        val spawn = WorkerSpawn { builder ->
            check(digest(Path.of(builder.command().first())) == expected) { "Candidate native host identity differs" }
            builder.start().also { child ->
                CodeModeParityText.write(System.err, "{\"type\":\"parity/identity\",\"nativeHostPid\":${child.pid()}}")
            }
        }
        return NativeCodeModeRuntime(spawn = spawn)
    }

    private fun digest(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            java.security.DigestInputStream(
                input,
                digest,
            ).use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
        }
        return java.util.HexFormat.of().formatHex(digest.digest())
    }
}

private const val GATEWAY_PROBE_ARGUMENTS = 5
