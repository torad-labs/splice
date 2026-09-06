import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Proves the release can carry its own JS engine on the existing JVM, without Codex or Node. */
class CodeModeEmbeddingSpike {
    @Test
    fun `one script suspends for independent client calls then continues from their results`() {
        val requests = mutableListOf<String>()
        Context.newBuilder("js")
            .allowHostAccess(HostAccess.NONE)
            .allowHostClassLookup { false }
            .allowPolyglotAccess(PolyglotAccess.NONE)
            .allowIO(IOAccess.NONE)
            .allowCreateThread(false)
            .allowCreateProcess(false)
            .allowNativeAccess(false)
            .option("engine.WarnInterpreterOnly", "false")
            .build().use { context ->
                context.getBindings("js").putMember("request", ProxyExecutable { args ->
                    requests.add(args.single().asString())
                    null
                })
                context.eval(
                    "js",
                    """
                    const pending = new Map();
                    let sequence = 0;
                    let answer;
                    const call = (name, args) => new Promise((resolve, reject) => {
                        const id = ++sequence;
                        pending.set(id, {resolve, reject});
                        request(JSON.stringify({id, name, args}));
                    });
                    const settle = (id, result) => {
                        const callback = pending.get(id);
                        pending.delete(id);
                        callback.resolve(JSON.parse(result));
                    };
                    (async () => {
                        const files = await Promise.all([
                            call("Read", {file_path: "a.txt"}),
                            call("Read", {file_path: "b.txt"})
                        ]);
                        answer = files.join("+");
                    })();
                    """.trimIndent(),
                )
                assertEquals(2, requests.size)
                assertEquals("undefined", context.eval("js", "typeof answer").asString())
                // A later client request supplies approved tool results; the model is not called.
                context.eval("js", "settle").execute(2, "\"B\"")
                assertEquals("undefined", context.eval("js", "typeof answer").asString())
                context.eval("js", "settle").execute(1, "\"A\"")
                assertEquals("A+B", context.eval("js", "answer").asString())
                assertEquals(0, context.eval("js", "pending.size").asInt())
                // The Java namespace exists even with lookup denied; test the capability, not its name.
                assertThrows(PolyglotException::class.java) {
                    context.eval("js", "Java.type('java.lang.String')")
                }
                assertFalse(context.eval("js", "typeof require !== 'undefined'").asBoolean())
                assertFalse(context.eval("js", "typeof fetch !== 'undefined'").asBoolean())
            }
    }
}
