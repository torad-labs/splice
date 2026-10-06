// NEW: streamed facts agree with the actual tree reader over the whole synthetic history.
package splice.app.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.PerfKeys
import java.nio.file.Files
import java.nio.file.Path

class PerfStreamingParityTest {
    @Test
    fun `streaming and tree readers agree on every row of the scale history`(@TempDir dir: Path) {
        val history = SyntheticPerfHistory(dir)
        history.create()
        val paths = Readers(history.file)
        val native = PerfRowDecode(PerfFieldNames(1_024L * 1_024))
        var count = 0
        listOf(history.file.resolveSibling("${history.file.fileName}.1"), history.file).forEach { file ->
            Files.newBufferedReader(file).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val tree = paths.tree(line)
                    val streamed = native.decode(line).getOrThrow()
                    assertEquals(tree.row, streamed.row, "every descriptive and numeric fact at row $count")
                    assertEquals(tree.drops, streamed.drops)
                    assertEquals(tree.numericBytes, streamed.numericBytes)
                    count++
                }
            }
        }
        assertEquals(SCALE_REQUESTS * 2, count, "both source generations are the denominator")
    }

    @Test
    fun `fallback and streaming preserve escaped optional numeric nested and torn facts`(@TempDir dir: Path) {
        val paths = Readers(dir.resolve("synthetic-perf.jsonl"))
        val rows = listOf(
            """{"ts":1,"outcome":"ok","model":"quo\"te\\tab\t雪","session":"😀","account":"a","turn":"t"}""",
            """{"ts":2,"unknown_number":9223372036854775807,"minimum":-9223372036854775808,"zero":0,"unknown_null":null,"unknown_flag":false}""",
            """{"ts":3,"in_tokens":1.0,"out_tokens":2e3,"cached_tokens":"4","metric":-7,"compact":"false"}""",
            """{"ts":4,"model":true,"session":123,"account":null,"outcome":false,"cache_cold":"true"}""",
            """{"ts":5,"unknown_object":{"ts":99,"metric":100},"unknown_array":[1,{"metric":2}],"turn":[]}""",
            """{"ts":6,"metric":1,"metric":null,"metric":2,"model":"first","model":"last"}""",
            """{"ts":7,"ts":17,"in_tokens":12,"account":"x"}""",
            """{"ts":8,"ts":18,"async_io_drops":"9"}""",
            """{"ts":9,"model":"bad�","outcome":"bad�","account":"bad�"}""",
            """{"ts":10,"metric":9223372036854775808,"compact":true}""",
            """{"ts":11,"outcome":"upstream_failed","model":"","req_bytes":30}""",
            """{"ts":"12","in_tokens":1}""",
            """{"ts":13.0,"metric":1}""",
            """{"ts":1e2,"metric":1}""",
            """{"ts":014,"metric":1}""",
            """{"ts":15,"model":"unterminated""",
            """{"ts":16} trailing""",
            """{"ts":17,"model":"escaped","snow_雪":3}""",
            """{"ts":18,"${PerfKeys.FIRST_BYTE}":123,"${PerfKeys.ARRIVAL_TO_FIRST_CLIENT_BYTE_MS}":234}""",
            """{"ts":19,"${PerfKeys.UPSTREAM_WRITE_TO_FIRST_BYTE_MS}":345}""",
            """{"ts":20,"unknown":"${"x".repeat(4_097)}"}""",
            """{"ts":21,${(0..64).joinToString(",") { "\"field_$it\":$it" }}}""",
            """{"ts":22,"turn":"trace-record","turn_id":"sent-record"}""",
            """{"ts":23,"turn_id":"123456789012"}""",
            """{"ts":24,"turn":null,"turn_id":null}""",
        )
        rows.forEachIndexed { index, line ->
            assertEquals(paths.tree(line), paths.streamed(line), "tree parity for edge row $index")
        }
    }

    @Test
    fun `request ownership remains charged in the retained perf cache`(@TempDir dir: Path) {
        val bare = dir.resolve("bare.jsonl")
        val owned = dir.resolve("owned.jsonl")
        val id = "x".repeat(3_000)
        Files.writeString(bare, "{\"ts\":1,\"outcome\":\"ok\"}\n")
        Files.writeString(owned, "{\"ts\":1,\"outcome\":\"ok\",\"turn_id\":\"$id\"}\n")
        val baseline = PerfRowsFileSource(bare)
        val source = PerfRowsFileSource(owned)
        baseline.window(0)
        assertEquals(id, source.window(0).rows.single().turnId)
        assertTrue(
            source.cachedBytes - baseline.cachedBytes >= id.length * PERF_CHAR_BYTES,
            "the one-off request id cannot escape the source's retained-string charge",
        )
        assertTrue(source.cachedBytes <= PERF_CACHE_BYTES)
    }

    @Test
    fun `distinct refused names discard canonical tables and remain charged`() {
        val pool = PerfFieldNames(384)
        pool.share("ts")
        val decoder = PerfRowDecode(pool)
        val factory = decoder.javaClass.getDeclaredField("factory").apply { isAccessible = true }
        repeat(256) { at ->
            val key = "unknown_$at"
            val before = factory.get(decoder)
            val decoded = decoder.decode("""{"ts":$at,"$key":$at}""").getOrThrow()
            assertEquals(at.toLong(), decoded.row.fields[key])
            assertTrue(pool.retainedBytes <= 384, "distinct names cannot expand the shared-name budget")
            assertTrue(decoded.numericBytes >= PERF_STRING_OVERHEAD_BYTES + key.length * PERF_CHAR_BYTES)
            assertNotSame(before, factory.get(decoder), "refused canonical names cannot survive in the factory")
        }
    }

    @Test
    fun `malformed scalars cannot retain names parsed before the field token is returned`() {
        val decoder = PerfRowDecode(PerfFieldNames(384))
        val factory = decoder.javaClass.getDeclaredField("factory").apply { isAccessible = true }
        repeat(256) { at ->
            val before = factory.get(decoder)
            assertTrue(decoder.decode("""{"unknown_$at":invalid}""").isFailure)
            assertNotSame(
                before,
                factory.get(decoder),
                "a failed parser may have already canonicalized an uncharged name",
            )
        }
    }

    /** Calls the actual legacy and selected decoders, not a test copy of their field mappings. */
    private class Readers(file: Path) {
        private val source = PerfRowsFileSource(file)
        private val type = source.javaClass.declaredClasses.single { it.simpleName == "Scan" }
        private val constructor = type.declaredConstructors.single().apply { isAccessible = true }
        private val selection = constructor.parameterTypes.single { it.isEnum }.enumConstants.first()
        private val scan = constructor.newInstance(source, 0L, selection, PerfRowsCache())
        private val legacy = type.getDeclaredMethod("decodeTree", String::class.java).apply { isAccessible = true }
        private val selected = type.getDeclaredMethod("decode", String::class.java).apply { isAccessible = true }

        fun tree(line: String): PerfCachedLine = requireNotNull(legacy.invoke(scan, line) as? PerfCachedLine)
        fun streamed(line: String): PerfCachedLine = requireNotNull(selected.invoke(scan, line) as? PerfCachedLine)
    }
}
