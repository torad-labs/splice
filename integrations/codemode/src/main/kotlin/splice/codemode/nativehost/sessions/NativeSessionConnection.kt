package splice.codemode.nativehost.sessions

import splice.codemode.nativehost.NativeConnection

/** The host connection a logical session opens on: the live one, or a fresh one once the last has died. */
internal fun interface NativeSessionConnection {
    suspend fun current(): NativeConnection
}
