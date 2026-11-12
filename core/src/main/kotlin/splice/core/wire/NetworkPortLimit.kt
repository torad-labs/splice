package splice.core.wire

// why: TCP and UDP encode port numbers in an unsigned 16-bit field.
internal const val NETWORK_PORT_MAX: Int = 65_535
