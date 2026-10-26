package splice.upstream.codemode.json

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.HexFormat

/** Expected diagnostics come from release-feature serde_json 1.0.149 from_slice and the real client witness. */
class CodeModeJsonBytesTest {
    @Test
    fun `malformed bytes preserve the source syntax precedence and byte position`() {
        val cases = listOf(
            "22ff22" to "invalid unicode code point at line 1 column 3",
            "7b7dff" to "trailing characters at line 1 column 3",
            "225cff22" to "invalid escape at line 1 column 3",
            "20ff" to "expected value at line 1 column 2",
            "22ff" to "EOF while parsing a string at line 1 column 2",
            "22ff0a22" to
                "control character (\\u0000-\\u001F) found while parsing a string at line 2 column 0",
            "22ff5c7122" to "invalid escape at line 1 column 4",
            "225c753178327822" to "invalid escape at line 1 column 7",
            "225c753122" to "EOF while parsing a string at line 1 column 5",
            "225c75c3a922" to "EOF while parsing a string at line 1 column 6",
            "225c75f09f988022" to "invalid escape at line 1 column 7",
            "225c7544383030c3a922" to "unexpected end of hex escape at line 1 column 8",
            "6e756cc3a9" to "expected ident at line 1 column 4",
            "22edb08022" to "invalid unicode code point at line 1 column 5",
        )
        for ((hex, expected) in cases) {
            val error =
                assertThrows(CodeModeJsonException::class.java) { CodeModeJson.parse(HexFormat.of().parseHex(hex)) }
            assertEquals(expected, error.diagnostic, hex)
        }
    }

    @Test
    fun `valid UTF-8 supplementary scalars remain values`() {
        assertEquals(JsonPrimitive("😀"), CodeModeJson.parse(HexFormat.of().parseHex("22f09f988022")))
        assertEquals(
            JsonPrimitive(String(Character.toChars(Character.MAX_CODE_POINT))),
            CodeModeJson.parse(HexFormat.of().parseHex("22f48fbfbf22")),
        )
    }
}
