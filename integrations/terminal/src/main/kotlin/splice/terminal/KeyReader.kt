// NEW: CW-1 — decode stdin bytes into Key. CSI arrows, a split sequence, and a lone ESC
// that must not block forever.
package splice.terminal

import java.io.InputStream

internal sealed class Key {
    internal data object Up : Key()
    internal data object Down : Key()
    internal data object Left : Key()
    internal data object Right : Key()
    internal data object Enter : Key()
    internal data object Space : Key()
    internal data object Escape : Key()
    internal data object CtrlC : Key()
    internal data object Backspace : Key()
    internal data class Char(val codepoint: Int) : Key()
}

/** Decodes stdin bytes into [Key]s for the prompt widgets; app hands in `System.in`. */
public class KeyReader(private val input: InputStream) {
    internal fun read(): Key {
        val first = input.read()
        if (first < 0) return Key.Escape
        return when (first) {
            BYTE_CTRL_C -> Key.CtrlC
            BYTE_LF, BYTE_CR -> Key.Enter
            BYTE_SPACE -> Key.Space
            BYTE_BS, BYTE_DEL -> Key.Backspace
            BYTE_ESC -> readEscape()
            else -> Key.Char(first)
        }
    }

    private fun readEscape(): Key {
        val second = readByteBounded()
        if (second != BYTE_CSI) return Key.Escape
        return when (readByteBounded()) {
            BYTE_UP -> Key.Up
            BYTE_DOWN -> Key.Down
            BYTE_RIGHT -> Key.Right
            BYTE_LEFT -> Key.Left
            else -> Key.Escape
        }
    }

    /** After ESC, do not block unbounded. available() plus a short wait covers a split CSI. */
    private fun readByteBounded(): Int {
        if (input.available() > 0) return input.read()
        val deadline = System.nanoTime() + ESC_WAIT_NS
        while (System.nanoTime() < deadline) {
            if (input.available() > 0) return input.read()
            Thread.sleep(ESC_POLL_MS)
        }
        return if (input.available() > 0) input.read() else -1
    }
}

private const val BYTE_CTRL_C = 3
private const val BYTE_BS = 8
private const val BYTE_LF = 10
private const val BYTE_CR = 13
private const val BYTE_ESC = 27
private const val BYTE_SPACE = 32
private const val BYTE_CSI = 91
private const val BYTE_UP = 65
private const val BYTE_DOWN = 66
private const val BYTE_RIGHT = 67
private const val BYTE_LEFT = 68
private const val BYTE_DEL = 127
private const val ESC_WAIT_NS = 50_000_000L
private const val ESC_POLL_MS = 5L
