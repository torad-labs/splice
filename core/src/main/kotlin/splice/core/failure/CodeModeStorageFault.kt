package splice.core.failure

/** Safe storage facts, carried separately from exception text that may quote conversation bytes. */
public enum class CodeModeStorageFault { LIMIT, INVALID, ACCESS, IO, DISK_FULL, HEAP }
