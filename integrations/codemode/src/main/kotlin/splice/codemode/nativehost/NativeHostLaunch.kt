package splice.codemode.nativehost

import java.nio.file.Path

// PORT-OF: protocol/src/shell_environment.rs NON_INHERITABLE_ENV_VARS at rust-v0.164.0.
private val NATIVE_NON_INHERITABLE_ENVIRONMENT = setOf(
    "CODEX_EXEC_SERVER_NOISE_AUTH_TOKEN",
    "NODE_REPL_AUTH_TOKEN",
    "CODEX_GUARDIAN_DECISIONS_API_KEY",
    "OPENAI_FEDERATION_RULE_ID",
    "OPENAI_IDENTITY_TOKEN_FILE",
    "OPENAI_WORKLOAD_IDENTITY_CONTEXT",
)

/** The official host inherits the environment except the source-defined restricted launch context. */
internal object NativeHostLaunch {
    fun command(executable: Path): ProcessBuilder =
        ProcessBuilder(executable.toString(), "--listen", "grpc+stdio://").apply {
            environment().keys.removeIf { asciiUppercase(it) in NATIVE_NON_INHERITABLE_ENVIRONMENT }
        }

    private fun asciiUppercase(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(if (character in 'a'..'z') character.uppercaseChar() else character)
        }
    }
}
