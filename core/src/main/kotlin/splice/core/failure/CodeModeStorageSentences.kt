package splice.core.failure

/** A storage refusal never claims the runtime failed to start or that committed state was lost. */
internal object CodeModeStorageSentences {
    fun said(fault: CodeModeStorageFault, head: String): Said = when (fault) {
        CodeModeStorageFault.LIMIT -> Said(
            "$head's code-mode history reached its storage limit, so this turn stopped",
            next = Next.Then("Resending cannot make room; the saved history needs operator attention."),
        )
        CodeModeStorageFault.INVALID -> Said(
            "$head's saved code-mode history is invalid, so this turn stopped",
            next = Next.Then("Resending cannot repair it; the saved history needs operator attention."),
        )
        CodeModeStorageFault.ACCESS -> Said(
            "$head cannot safely access its code-mode state directory, so this turn stopped",
            next = Next.Then("The directory permissions need operator attention."),
        )
        CodeModeStorageFault.IO -> Said(
            "$head could not read or save its code-mode state because storage I/O failed",
            next = Next.Resend("Check the state storage before sending this turn again."),
        )
        CodeModeStorageFault.DISK_FULL -> Said(
            "$head could not save its code-mode state because the state disk is full",
            next = Next.Resend("Free space on the state disk before sending this turn again."),
        )
        CodeModeStorageFault.HEAP -> Said(
            "$head could not read or save its code-mode state because memory was unavailable",
            next = Next.Resend(SEND_LATER),
        )
    }
}

/** Safe storage facts, carried separately from exception text that may quote conversation bytes. */
public enum class CodeModeStorageFault { LIMIT, INVALID, ACCESS, IO, DISK_FULL, HEAP }
