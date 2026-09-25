// NEW: `splice setup`'s local-model step against a FAKE rig — never the real one, its installer or
// nvidia-smi: the real one downloads an 8 GB model, and on the operator's box a model already serves on
// rig's port. When to offer, what declining leaves, rig's exit codes in words, and — through the real
// `splice add` machinery and the real topology loader — the exact row a successful run writes.
package splice.app.cli.setup

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.configuration.add.AddLogin
import splice.configuration.add.AddPorts
import splice.configuration.add.AddPrompter
import splice.configuration.add.AddVerb
import splice.configuration.add.DaemonRestart
import splice.configuration.add.DaemonUpProbe
import splice.configuration.add.RuntimeHead
import splice.configuration.add.WrapperInstall
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.terminal.TerminalOutput
import splice.core.topology.Dialect
import splice.core.util.EnvReader
import splice.terminal.ConfirmPrompt
import splice.terminal.ConsolePresence
import splice.terminal.MultiSelectOutcome
import splice.terminal.PulseScheduler
import splice.terminal.SelectOutcome
import splice.terminal.Spinner
import splice.terminal.WizardFrame
import splice.topology.TopologyLoader
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

private const val CARD = "NVIDIA GeForce RTX 5090"
private const val PREPARE_OK =
    """{"card":{"index":0,"name":"$CARD","memoryMiB":32607},"supported":true,"prebuilt":true}"""
private const val UP_OK = """{"steps":{"fetch":"fetched","build":"present"},"start":"started","linger":true}"""
private const val RETRY = "retry by hand: rig up bonsai-2-27b, then splice setup again"
private const val KEY_ENV = "BONSAI_API_KEY"
private const val WINDOW = 245_760L

class SetupLocalModelTest {

    private val out = mutableListOf<String>()
    private val chrome = StringBuilder()
    private val asked = mutableListOf<String>()
    private val added = mutableListOf<RuntimeHead>()
    private var gpuCalls = 0

    private fun prompts(console: Boolean = true, answer: Boolean = true, spinner: Spinner = quietSpinner()) =
        SetupPrompts(
            frame = WizardFrame(
                out = chrome,
                ask = ConfirmPrompt { question, _ ->
                    asked += question
                    answer
                },
            ),
            spinner = spinner,
            hasConsole = ConsolePresence { console },
        )

    private fun quietSpinner() = Spinner(StringBuilder(), tty = false)

    private fun step(
        rig: Rig = FakeRig(),
        prompts: SetupPrompts = prompts(),
        cards: List<String> = listOf(CARD),
        platform: HostPlatform = HostPlatform("Linux", "amd64"),
        add: LocalHeadAdd = LocalHeadAdd { head ->
            added += head
            true
        },
    ) = SetupLocalModel(
        prompts = prompts,
        env = EnvReader { null },
        restart = DaemonRestart { error("the step restarts only through the add it hands the head to") },
        rig = rig,
        gpu = GpuProbe {
            gpuCalls += 1
            cards
        },
        platform = platform,
        add = add,
        out = TerminalOutput { out += it },
    )

    private fun log() = out.joinToString("\n")

    // ── when to offer ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `not offered without a console, and nvidia-smi is never asked`(@TempDir home: Path) {
        assertFalse(step(prompts = prompts(console = false)).offer(home.resolve("splice.toml")))
        assertEquals(0, gpuCalls)
        assertTrue(asked.isEmpty(), asked.toString())
    }

    @Test
    fun `not offered without an NVIDIA card`(@TempDir home: Path) {
        assertFalse(step(cards = emptyList()).offer(home.resolve("splice.toml")))
        assertEquals(1, gpuCalls)
        assertTrue(asked.isEmpty(), asked.toString())
    }

    @Test
    fun `not offered off Linux x86_64`(@TempDir home: Path) {
        assertFalse(step(platform = HostPlatform("Mac OS X", "aarch64")).offer(home.resolve("splice.toml")))
        assertEquals(0, gpuCalls)
        assertTrue(asked.isEmpty(), asked.toString())
    }

    @Test
    fun `not offered when bonsai is already configured, and says so once`(@TempDir home: Path) {
        val path = home.resolve("splice.toml")
        Files.writeString(path, BONSAI_TOPOLOGY)
        assertFalse(step().offer(path))
        assertTrue(asked.isEmpty(), asked.toString())
        assertEquals(0, gpuCalls, "the card is not probed for a question that will not be asked")
        assertEquals(1, Regex("'bonsai' is already configured").findAll(chrome).count(), chrome.toString())
    }

    @Test
    fun `the question names the card, the install dir, the model and its size, and defaults to no`(
        @TempDir home: Path,
    ) {
        var default: Boolean? = null
        val prompts = SetupPrompts(
            frame = WizardFrame(
                out = chrome,
                ask = ConfirmPrompt { question, fallback ->
                    asked += question
                    default = fallback
                    false
                },
            ),
            hasConsole = ConsolePresence { true },
        )
        assertFalse(step(prompts = prompts).offer(home.resolve("splice.toml")))
        val question = asked.single()
        val parts = listOf(CARD, "~/.local/share/rig", "bonsai-2-27b and its engine", "about 9 GB", "about 18 GB of disk")
        for (part in parts + "serves it on this machine") {
            assertTrue(part in question, "'$part' missing from: $question")
        }
        assertEquals(false, default, "the question defaults to NO")
    }

    @Test
    fun `declining changes nothing`(@TempDir home: Path) {
        val path = home.resolve("splice.toml")
        Files.writeString(path, "[daemon]\ncontrol_port = 3096\n")
        val rig = FakeRig()
        val step = step(rig = rig, prompts = prompts(answer = false))
        val chosen = step.offer(path)
        runBlocking { step.install(chosen) }
        assertFalse(chosen)
        assertEquals(emptyList<String>(), step.summary(chosen), "no Summary line for a declined model")
        assertTrue(rig.calls.isEmpty(), rig.calls.toString())
        assertTrue(added.isEmpty())
        assertEquals("[daemon]\ncontrol_port = 3096\n", Files.readString(path))
        assertFalse(Files.exists(home.resolve("keys.toml")))
    }

    @Test
    fun `chosen, the Summary carries one line naming the download`() {
        val summary = step().summary(true)
        assertEquals(1, summary.size, summary.toString())
        val named = listOf("about 9 GB", "about 18 GB of disk", "claude-bonsai")
        assertTrue(named.all { it in summary.single() }, summary.toString())
    }

    @Test
    fun `the wizard asks after the tick-list, summarizes it, and runs it after the heads land`(@TempDir home: Path) {
        val events = mutableListOf<String>()
        val prompts = wizardPrompts(events)
        val local = step(
            rig = FakeRig(onCall = { events += "rig $it" }),
            prompts = prompts,
            add = LocalHeadAdd {
                events += "add bonsai"
                true
            },
        )
        runWizard(home, prompts, local, events)
        // The offer after the tick-list, both answers before anything runs, and rig only after the
        // ticked heads landed and the daemon restarted for them.
        val expected = listOf(
            "pick heads",
            "ask local",
            "ask Install now?",
            "add codex",
            "restart",
            "rig version",
            "rig prepare",
            "rig up",
            "rig describe",
            "add bonsai",
        )
        assertEquals(expected, events)
        val summary = chrome.substring(chrome.indexOf("Summary"))
        assertTrue("Local model:" in summary && "about 9 GB" in summary, chrome.toString())
    }

    /** Every question answered yes, `codex` ticked, each answer recorded in [events]. */
    private fun wizardPrompts(events: MutableList<String>) = SetupPrompts(
        frame = WizardFrame(
            out = chrome,
            ask = ConfirmPrompt { question, _ ->
                events += if (question.startsWith("Run a local model")) "ask local" else "ask $question"
                true
            },
        ),
        choose = { options, index -> SelectOutcome.Chosen(options[index].value) },
        pickHeads = HeadPicker { _, _ ->
            events += "pick heads"
            MultiSelectOutcome.Chosen(listOf("codex"))
        },
        spinner = quietSpinner(),
        hasConsole = ConsolePresence { true },
    )

    /** The whole wizard under a temporary user.home, the heads and the restart recorded in [events]. */
    private fun runWizard(home: Path, prompts: SetupPrompts, local: SetupLocalModel, events: MutableList<String>) {
        val previousHome = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        try {
            val share = home.resolve(".local").resolve("share").resolve("splice")
            Files.createDirectories(share)
            Files.writeString(share.resolve("splice-launch"), "#!/usr/bin/env bash\n")
            runBlocking {
                SetupCommand(
                    prompts = prompts,
                    loginHead = HeadSignIn { error("no head here needs a sign-in") },
                    detect = { SetupFacts(emptySet(), emptySet(), false, false, SetupStart.OpenRouter) },
                    addProfile = ProfileAdd { name ->
                        events += "add $name"
                        true
                    },
                    restart = DaemonRestart {
                        events += "restart"
                        true
                    },
                    localModel = local,
                ).setup()
            }
        } finally {
            System.setProperty("user.home", previousHome)
        }
    }

    // ── rig prepare ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `prepare exit 1 prints rig's stderr as-is and adds no head`() {
        val missing = "missing on this machine: git, cmake, ninja, cuda toolkit (nvcc) (glibc 2.31 on this machine)"
        refusedAtPrepare(RigRun(1, "", "$missing\n"), missing)
    }

    @Test
    fun `prepare exit 3 prints rig's own sentence and adds no head`() {
        refusedAtPrepare(RigRun(3, "", "compute capability not measured\n"), "compute capability not measured")
    }

    @Test
    fun `prepare exit 4 says to update the driver and adds no head`() {
        refusedAtPrepare(
            RigRun(4, "", "driver too old\n"),
            "the NVIDIA driver is older than the CUDA runtime the engine needs — update the driver",
        )
    }

    @Test
    fun `an unmapped prepare exit names its code and adds no head`() {
        refusedAtPrepare(RigRun(9, "", "something new\n"), "rig prepare exited 9")
    }

    private fun refusedAtPrepare(prepare: RigRun, sentence: String) {
        val rig = FakeRig(prepare = prepare)
        runBlocking { step(rig = rig).install(true) }
        assertTrue(sentence in log(), log())
        assertTrue(RETRY in log(), log())
        assertEquals(listOf("version", "prepare"), rig.calls, "nothing runs after a refused prepare")
        assertTrue(added.isEmpty())
    }

    @Test
    fun `a no-prebuilt sentence is shown verbatim and the run goes on`() {
        val sentence = "glibc 2.31 is below the prebuilt engine's floor (2.35); rig will compile it (5-20 min)"
        val rig = FakeRig(prepare = RigRun(0, """{"prebuilt":false,"noPrebuilt":"$sentence"}""", ""))
        runBlocking { step(rig = rig).install(true) }
        assertTrue(out.any { it.trim() == sentence }, log())
        assertEquals(1, added.size, log())
    }

    // ── rig up ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an up failure prints rig's last lines and the head's log, and adds no head`() {
        val stderr = "== start\nthe head did not come up on :8099\n"
        val rig = FakeRig(up = RigRun(1, "", stderr))
        runBlocking { step(rig = rig).install(true) }
        assertTrue("the head did not come up on :8099" in log(), log())
        assertTrue("its log: ~/.local/share/rig/local/logs/bonsai-2-27b.log" in log(), log())
        assertFalse("describe" in rig.calls, rig.calls.toString())
        assertTrue(added.isEmpty())
    }

    @Test
    fun `a failed rig step stops its spinner with a cross, and a passed one with the check`() {
        val shown = StringBuilder()
        val prompts = prompts(spinner = Spinner(shown, tty = true, scheduler = { AutoCloseable { } }))
        runBlocking { step(rig = FakeRig(up = RigRun(1, "", "no room\n")), prompts = prompts).install(true) }
        val lines = shown.split('\n').map { it.substringAfterLast("\u001B[2K") }
        assertTrue(lines.any { "✓" in it && "rig prepare: the card is ready" in it }, shown.toString())
        assertTrue(lines.any { "✗" in it && "rig up bonsai-2-27b: stopped" in it }, shown.toString())
    }

    @Test
    fun `up exit 2 is worded as a refused restart, and up passes prepare's codes through`() {
        runBlocking { step(rig = FakeRig(up = RigRun(2, "", ""))).install(true) }
        assertTrue("rig refused to restart a head that is serving" in log(), log())
        runBlocking { step(rig = FakeRig(up = RigRun(3, "", ""))).install(true) }
        assertTrue("this card is not one rig supports yet" in log(), log())
        assertTrue(added.isEmpty())
    }

    @Test
    fun `linger false prints the note with rig's own command`() {
        runBlocking { step(rig = FakeRig(up = RigRun(0, """{"start":"started","linger":false}""", ""))).install(true) }
        assertTrue(out.any { "stops when you log out" in it && "sudo loginctl enable-linger \$USER" in it }, log())
    }

    @Test
    fun `linger null or absent says nothing`() {
        runBlocking { step(rig = FakeRig(up = RigRun(0, """{"start":"started","linger":null}""", ""))).install(true) }
        runBlocking { step(rig = FakeRig(up = RigRun(0, """{"start":"left-running"}""", ""))).install(true) }
        assertFalse(out.any { "loginctl" in it }, log())
        assertEquals(2, added.size, log())
    }

    @Test
    fun `rig up's step lines drive the spinner`() {
        val screen = StringBuilder()
        val spinner = Spinner(screen, tty = true, scheduler = PulseScheduler { AutoCloseable {} })
        runBlocking { step(prompts = prompts(spinner = spinner)).install(true) }
        for (step in listOf("fetch", "fetching model.gguf", "build", "derive", "unit", "start")) {
            assertTrue("rig up bonsai-2-27b: $step" in screen, "'$step' never reached the spinner: $screen")
        }
    }

    // ── rig install ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a missing rig is installed with its exact command shown first`() {
        val rig = FakeRig(versions = ArrayDeque(listOf(127, 0)))
        runBlocking { step(rig = rig).install(true) }
        assertTrue(RIG_INSTALL in chrome, chrome.toString())
        assertEquals(listOf("version", "install", "version", "prepare", "up bonsai-2-27b", "describe"), rig.calls)
        assertEquals(1, added.size, log())
    }

    @Test
    fun `a failed install stops the step with its stderr tail`() {
        val rig = FakeRig(versions = ArrayDeque(listOf(127)), install = RigRun(1, "", "rig install: sha256 mismatch\n"))
        runBlocking { step(rig = rig).install(true) }
        assertTrue("rig install: sha256 mismatch" in log(), log())
        assertEquals(listOf("version", "install"), rig.calls)
        assertTrue(added.isEmpty())
    }

    // ── rig describe ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `describe maps server facts onto the head, reasoning_effort negated`() {
        runBlocking { step().install(true) }
        val head = added.single()
        assertEquals("bonsai", head.key)
        assertEquals("bonsai-2-27b", head.modelId)
        assertEquals("Bonsai 2 27B", head.modelLabel)
        assertEquals(WINDOW, head.contextWindow, "the ADVERTISED window, never model_ctx")
        assertFalse(head.emitReasoningEffort, "rejects_reasoning_effort = true means do NOT emit it")
        assertTrue(head.slotAffinity)
        assertTrue(head.anyModelId)
    }

    @Test
    fun `a dialect other than openai-chat is refused in words`() {
        runBlocking { step(rig = FakeRig(describe = RigRun(0, describe(dialect = "anthropic"), ""))).install(true) }
        assertTrue("the local head speaks openai-chat only" in log(), log())
        assertTrue(added.isEmpty())
    }

    @Test
    fun `a rig that throws mid-step is reported and never escapes the wizard`() {
        val rig = object : Rig by FakeRig() {
            override fun describe(head: String): RigRun = error("describe blew up")
        }
        runBlocking { step(rig = rig).install(true) }
        assertTrue("local model not set up" in log(), log())
        assertTrue(added.isEmpty())
    }

    // ── the row, through the real `splice add` machinery ─────────────────────────────────────────

    @Test
    fun `a successful run writes exactly the bonsai row and restarts once`(@TempDir home: Path) {
        val restarts = intArrayOf(0)
        val topology = served(home, restarts) { env ->
            val text = Files.readString(TopologyLoader.configPath(env))
            TopologyLoader.parse(text)
        }
        val provider = topology.providers.getValue("bonsai")
        assertEquals(Dialect.OPENAI_CHAT, provider.dialect)
        assertTrue(provider.baseUrl.startsWith("http://127.0.0.1:") && provider.baseUrl.endsWith("/v1"))
        assertEquals("api-key", provider.auth.kind)
        assertEquals(KEY_ENV, provider.auth.env)
        assertEquals(false, provider.quirks.reasoningEffort)
        assertEquals(true, provider.quirks.slotAffinity)
        val model = provider.models.single()
        assertEquals("bonsai-2-27b", model.id)
        assertEquals("Bonsai 2 27B", model.label)
        assertEquals(WINDOW, model.contextWindow)
        val head = topology.heads.getValue("bonsai")
        assertEquals("bonsai", head.provider)
        assertEquals("bonsai-2-27b", head.pinnedModel)
        assertEquals(WINDOW, head.contextWindow)
        assertEquals("claude-bonsai", head.claude.command)
        assertEquals(1, restarts[0], "the add restarts the running daemon once so the head comes up")
        assertTrue(out.any { "Launch" in it && "claude-bonsai" in it }, log())
    }

    @Test
    fun `the placeholder key is written when absent`(@TempDir home: Path) {
        val stored = served(home) { env -> KeyStore(KeyStorePath.defaultPath(env)).read(KEY_ENV) }
        assertEquals("local-runtime-no-auth", stored)
    }

    @Test
    fun `the placeholder key leaves an operator's value alone`(@TempDir home: Path) {
        val stored = served(home, seedKey = "operator-value") { env ->
            KeyStore(KeyStorePath.defaultPath(env)).read(KEY_ENV)
        }
        assertEquals("operator-value", stored)
    }

    /** One whole run: a fake llama-server on a port it bound itself, a fake rig that describes it, and
     *  the REAL AddVerb with fake ports (no wrapper link, no real daemon) under a hermetic SPLICE_CONFIG
     *  and a temporary user.home (the control-port resolution reads the state root under it). */
    private fun <T> served(
        home: Path,
        restarts: IntArray = intArrayOf(0),
        seedKey: String? = null,
        read: (EnvReader) -> T,
    ): T {
        val previousHome = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        try {
            return servedAt(home, restarts, seedKey, read)
        } finally {
            System.setProperty("user.home", previousHome)
        }
    }

    private fun <T> servedAt(home: Path, restarts: IntArray, seedKey: String?, read: (EnvReader) -> T): T {
        val env = EnvReader { name -> if (name == "SPLICE_CONFIG") home.resolve("splice.toml").toString() else null }
        TopologyLoader.loadOrMaterialize(TopologyLoader.configPath(env))
        seedKey?.let { KeyStore(KeyStorePath.defaultPath(env)).write(KEY_ENV, it) }
        val server = fakeLlamaServer()
        try {
            val base = "http://127.0.0.1:${server.address.port}/v1"
            val sink = TerminalOutput { out += it }
            val verb = AddVerb(
                sink,
                sink,
                AddPorts(
                    login = AddLogin { _, _, _ -> error("a runtime head never signs in") },
                    install = WrapperInstall { _, _ -> true },
                    restart = DaemonRestart {
                        restarts[0] += 1
                        true
                    },
                    daemonUp = DaemonUpProbe { true },
                    prompt = AddPrompter { _, default -> default },
                ),
            )
            val rig = FakeRig(describe = RigRun(0, describe(baseUrl = base), ""))
            runBlocking { step(rig = rig, add = LocalHeadAdd { head -> verb.addRuntime(head, env) }).install(true) }
            return read(env)
        } finally {
            server.stop(0)
        }
    }
}

/** A llama-server stand-in on a port it bound itself: `/v1/models` lists one model, the rest is `{}`. */
private fun fakeLlamaServer(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/v1") { exchange ->
        val body = if (exchange.requestURI.path == "/v1/models") """{"data":[{"id":"/rig/model.gguf"}]}""" else "{}"
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    start()
}

/** rig as its contract says it answers, every call recorded; each answer overridable per test. */
private class FakeRig(
    private val versions: ArrayDeque<Int> = ArrayDeque(listOf(0)),
    private val install: RigRun = RigRun(0, "rig install: rig 0.1.2 in ~/.local/share/rig\n", ""),
    private val prepare: RigRun = RigRun(0, PREPARE_OK, ""),
    private val up: RigRun = RigRun(0, UP_OK, ""),
    private val describe: RigRun = RigRun(0, describe(), ""),
    private val onCall: (String) -> Unit = {},
) : Rig {
    val calls = mutableListOf<String>()

    private fun called(verb: String) {
        calls += verb
        onCall(verb.substringBefore(' '))
    }

    override fun version(): RigRun {
        called("version")
        return RigRun(versions.removeFirstOrNull() ?: 0, "rig 0.1.2\n", "")
    }

    override fun install(): RigRun = install.also { called("install") }

    override fun prepare(): RigRun = prepare.also { called("prepare") }

    override fun up(head: String, progress: RigProgress): RigRun {
        called("up $head")
        UP_STDERR.forEach { progress(it) }
        return up
    }

    override fun describe(head: String): RigRun = describe.also { called("describe") }
}

private val UP_STDERR = listOf(
    "== prepare",
    "== fetch",
    "fetching model.gguf",
    "== build",
    "== derive",
    "== unit",
    "== start",
)

/** `rig describe bonsai-2-27b` with every field rig prints, most of which splice must ignore. */
private fun describe(baseUrl: String = "http://127.0.0.1:8099/v1", dialect: String = "openai-chat") = """
{ "name": "bonsai-2-27b", "title": "Bonsai 2 27B", "dialect": "$dialect", "port": 8099, "base_url": "$baseUrl",
  "served_file": "model.gguf", "undrived": null, "engine_commit": "abc1234",
  "speculative": {"type": "draft-mtp", "file": null, "n_max": 2}, "model_ctx": 262144, "advertise_ctx": $WINDOW,
  "supported_archs": ["sm_120", "sm_90"], "this_gpu": 0, "this_arch": "sm_120", "supported": true, "built": true,
  "pack_verified": true, "unit_installed": true, "unit_active": true, "serving": true,
  "server_facts": {"rejects_reasoning_effort": true, "slot_pinning": true, "any_model_id": true},
  "sampling": ["--temp", "1.0"], "repo": "/home/u/.local/share/rig", "head_dir": "/home/u/.local/share/rig/heads" }
"""

private val BONSAI_TOPOLOGY = """
[daemon]
control_port = 3096
[providers.bonsai]
dialect = "openai-chat"
base_url = "http://127.0.0.1:8099/v1"
auth = { kind = "api-key", env = "BONSAI_API_KEY" }
[[providers.bonsai.models]]
id = "bonsai-2-27b"
context_window = 245760
[heads.bonsai]
provider = "bonsai"
port = 3100
discovery_prefix = "claude-bonsai--"
pinned_model = "bonsai-2-27b"
""".trimIndent() + "\n"
