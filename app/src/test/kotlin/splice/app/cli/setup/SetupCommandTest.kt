// NEW: CW-7 / CW-10 — splice setup on the prompt toolkit. Headless topology is the
// captured pre-campaign oracle; declining confirm writes nothing. Extra heads tick
// from AddProfiles and install through AddCommand.
package splice.app.cli.setup

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.add.AddProfiles
import splice.app.cli.prompt.ConfirmPrompt
import splice.app.cli.prompt.MultiSelectOutcome
import splice.app.cli.prompt.SelectOutcome
import splice.app.cli.prompt.Spinner
import splice.app.cli.prompt.WizardFrame
import splice.app.cli.upgrade.DaemonRestart
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class SetupCommandTest {

    @Test
    fun `headless setup writes the captured pre-campaign topology`(@TempDir home: Path) {
        val topology = withHome(home) {
            seedShim(home)
            runBlocking { SetupCommand(loginHead = NO_REAL_LOGIN).setup() }
            Files.readString(home.resolve(".config").resolve("splice").resolve("splice.toml"))
        }
        assertEquals(HEADLESS_ORACLE, topology)
    }

    @Test
    fun `declining confirm leaves the topology file absent`(@TempDir home: Path) {
        val path = home.resolve(".config").resolve("splice").resolve("splice.toml")
        var asked = 0
        withHome(home) {
            seedShim(home)
            runBlocking {
                SetupCommand(
                    prompts = SetupPrompts(
                        frame = WizardFrame(
                            out = StringBuilder(),
                            ask = ConfirmPrompt { _, _ ->
                                asked += 1
                                false
                            },
                        ),
                        choose = { options, index -> SelectOutcome.Chosen(options[index].value) },
                        spinner = Spinner(StringBuilder(), tty = false),
                    ),
                    loginHead = NO_REAL_LOGIN,
                    detect = { emptyFacts() },
                ).setup()
            }
        }
        assertEquals(1, asked, "Install now? must be asked once")
        assertFalse(Files.exists(path), "declined confirm must write nothing")
    }

    @Test
    fun `intro summary and outro run in order`(@TempDir home: Path) {
        val chrome = StringBuilder()
        withHome(home) {
            seedShim(home)
            runBlocking {
                SetupCommand(
                    prompts = SetupPrompts(
                        frame = WizardFrame(out = chrome, ask = ConfirmPrompt { _, d -> d }),
                        choose = { options, index -> SelectOutcome.Chosen(options[index].value) },
                        spinner = Spinner(StringBuilder(), tty = false),
                    ),
                    loginHead = NO_REAL_LOGIN,
                    detect = { emptyFacts() },
                ).setup()
            }
        }
        val text = chrome.toString()
        val intro = text.indexOf("splice setup")
        val summary = text.indexOf("Summary")
        val outro = text.indexOf("Toolkit ready!")
        assertTrue(intro >= 0 && summary > intro && outro > summary, text)
        assertFalse(text.contains("Detected"), "fresh machine prints no Detected line")
    }

    @Test
    fun `the closing block still names every affordance, by command`(@TempDir home: Path) {
        val log = captureStdout {
            withHome(home) {
                seedShim(home)
                runBlocking { SetupCommand(loginHead = NO_REAL_LOGIN).setup() }
            }
        }
        // Pinned by COMMAND, not by label. The 2026-09-22 redesign dropped the Launch/Dashboard/
        // Status/Checkup labels — the operator reads the command itself now — and the old arm failed
        // on the wording while the real regression it should catch is an AFFORDANCE going missing.
        // It nearly did: that cut lost the dashboard entirely and this arm is what found it.
        assertTrue("splice status" in log, log)
        assertTrue("splice doctor" in log, log)
        assertTrue("splice dashboard" in log, log)
        assertTrue("anything wrong prints its fix" in log)
        assertTrue("Setup complete." in log, "the close must still announce itself: $log")
    }

    @Test
    fun `starter topology keeps OpenRouter key hygiene`(@TempDir home: Path) {
        val topology = withHome(home) {
            seedShim(home)
            runBlocking { SetupCommand(loginHead = NO_REAL_LOGIN).setup() }
            Files.readString(home.resolve(".config").resolve("splice").resolve("splice.toml"))
        }
        assertTrue("env = \"OPENROUTER_API_KEY\"" in topology)
        assertFalse("chatgpt-oauth" in topology)
    }

    @Test
    fun `sign-in walk still prints the unofficial-OAuth warning`(@TempDir home: Path) {
        val log = captureStdout {
            withHome(home) {
                seedShim(home)
                seedOauthTopology(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            choose = { options, index -> SelectOutcome.Chosen(options[index].value) },
                            spinner = Spinner(tty = false),
                        ),
                        loginHead = { true },
                    ).setup()
                }
            }
        }
        assertTrue("unofficial; use at your own risk" in log, log)
        assertTrue("own credential file" in log, log)
    }

    @Test
    fun `two different picks produce two different Summary bodies`(@TempDir home: Path) {
        val openRouter = summaryFor(home, SetupStart.OpenRouter)
        val oauth = summaryFor(home, SetupStart.OAuth("chatgpt-oauth"))
        assertTrue(openRouter != oauth, "discarding the pick would make both Summaries identical")
        assertTrue("Wrapper commands under" in openRouter, openRouter)
        assertTrue("Wrapper commands under" in oauth, oauth)
        assertTrue("splice add chatgpt-oauth" in oauth, oauth)
        assertFalse("splice add chatgpt-oauth" in openRouter, openRouter)
    }

    @Test
    fun `Existing pick on a home with no topology still names the starter`(@TempDir home: Path) {
        val text = summaryFor(home, SetupStart.Existing)
        assertTrue("Starter topology" in text, text)
        assertTrue("Wrapper commands under" in text, text)
        assertFalse("already present" in text, text)
    }

    @Test
    fun `declining confirm leaves a seeded topology byte-identical`(@TempDir home: Path) {
        withHome(home) {
            seedShim(home)
            seedOauthTopology(home)
            val path = home.resolve(".config").resolve("splice").resolve("splice.toml")
            val before = Files.readString(path)
            runBlocking {
                SetupCommand(
                    prompts = SetupPrompts(
                        frame = WizardFrame(
                            out = StringBuilder(),
                            ask = ConfirmPrompt { _, _ -> false },
                        ),
                        choose = { options, index -> SelectOutcome.Chosen(options[index].value) },
                        spinner = Spinner(StringBuilder(), tty = false),
                    ),
                    loginHead = NO_REAL_LOGIN,
                    detect = { emptyFacts() },
                ).setup()
            }
            assertEquals(before, Files.readString(path))
        }
    }

    @Test
    fun `cancelling at select writes no topology and returns true`(@TempDir home: Path) {
        val path = home.resolve(".config").resolve("splice").resolve("splice.toml")
        val ok = withHome(home) {
            seedShim(home)
            runBlocking {
                SetupCommand(
                    prompts = SetupPrompts(
                        frame = WizardFrame(out = StringBuilder(), ask = ConfirmPrompt { _, d -> d }),
                        choose = { _, _ -> SelectOutcome.Cancelled },
                        spinner = Spinner(StringBuilder(), tty = false),
                    ),
                    loginHead = NO_REAL_LOGIN,
                    detect = { emptyFacts() },
                ).setup()
            }
        }
        assertTrue(ok)
        assertFalse(Files.exists(path))
    }

    @Nested
    inner class Heads {
        @Test
        fun `every AddProfiles entry is offered or excluded by name`(@TempDir home: Path) {
            val offered = mutableListOf<String>()
            val chrome = StringBuilder()
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            frame = WizardFrame(out = chrome, ask = ConfirmPrompt { _, d -> d }),
                            pickHeads = HeadPicker { options, _ ->
                                offered += options.map { it.value }
                                MultiSelectOutcome.Chosen(emptyList())
                            },
                        ),
                        loginHead = NO_REAL_LOGIN,
                        addProfile = ProfileAdd { error("empty tick must not add") },
                        detect = { emptyFacts() },
                    ).setup()
                }
            }
            val log = chrome.toString()
            val catalog = AddProfiles().catalog().map { it.name }.toSet()
            val named = catalog.filter { name -> "splice add $name" in log }.toSet()
            assertEquals(catalog, offered.toSet() + named, "catalog=$catalog offered=$offered named=$named log=$log")
            assertTrue("api-key" in named, log)
            assertFalse("api-key" in offered)
            // V4-175: `claude` is TICKABLE now. It was excluded with "needs a name", which was
            // never true of that row (AddPrepare.kt:42 falls back to profile.headKey, and the
            // catalogue gives it `claude-splice`), and the wrong reason is what kept the lane
            // choice off the wizard. The invariant above — offered OR excluded by name, never
            // silently absent — is unchanged and still what this arm is for.
            assertTrue("claude" in offered, log)
            assertFalse("claude" in named, log)
        }

        @Test
        fun `headless setup adds no heads and restarts zero times`(@TempDir home: Path) {
            val added = mutableListOf<String>()
            var restarts = 0
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        loginHead = NO_REAL_LOGIN,
                        addProfile = ProfileAdd { name ->
                            added += name
                            true
                        },
                        restart = DaemonRestart {
                            restarts += 1
                            true
                        },
                    ).setup()
                }
            }
            assertEquals(emptyList<String>(), added)
            assertEquals(0, restarts)
        }

        @Test
        fun `one failing head does not abort the others`(@TempDir home: Path) {
            val added = mutableListOf<String>()
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            pickHeads = HeadPicker { _, _ ->
                                MultiSelectOutcome.Chosen(listOf("codex", "grok", "kimi"))
                            },
                        ),
                        loginHead = NO_REAL_LOGIN,
                        addProfile = ProfileAdd { name ->
                            added += name
                            name != "grok"
                        },
                        restart = DaemonRestart { true },
                        detect = { emptyFacts() },
                    ).setup()
                }
            }
            assertEquals(listOf("codex", "grok", "kimi"), added)
        }

        @Test
        fun `daemon restarts at most once after adding heads`(@TempDir home: Path) {
            var restarts = 0
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            pickHeads = HeadPicker { _, _ -> MultiSelectOutcome.Chosen(listOf("codex", "kimi")) },
                        ),
                        loginHead = NO_REAL_LOGIN,
                        addProfile = ProfileAdd { true },
                        restart = DaemonRestart {
                            restarts += 1
                            true
                        },
                        detect = { emptyFacts() },
                    ).setup()
                }
            }
            assertEquals(1, restarts)
        }

        @Test
        fun `Summary names every ticked head`(@TempDir home: Path) {
            val chrome = StringBuilder()
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            frame = WizardFrame(out = chrome, ask = ConfirmPrompt { _, _ -> false }),
                            pickHeads = HeadPicker { _, _ -> MultiSelectOutcome.Chosen(listOf("muse", "deepseek")) },
                        ),
                        loginHead = NO_REAL_LOGIN,
                        detect = { emptyFacts() },
                    ).setup()
                }
            }
            val text = chrome.toString()
            assertTrue("Heads to add: muse, deepseek" in text, text)
        }

        @Test
        fun `already-installed head is shown and not tickable`(@TempDir home: Path) {
            val offered = mutableListOf<String>()
            val chrome = StringBuilder()
            withHome(home) {
                seedShim(home)
                seedGrok(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            frame = WizardFrame(out = chrome, ask = ConfirmPrompt { _, d -> d }),
                            pickHeads = HeadPicker { options, _ ->
                                offered += options.map { it.value }
                                MultiSelectOutcome.Chosen(emptyList())
                            },
                        ),
                        loginHead = NO_REAL_LOGIN,
                        addProfile = ProfileAdd { error("empty tick must not add") },
                        detect = { emptyFacts() },
                    ).setup()
                }
            }
            assertFalse("grok" in offered, offered.toString())
            assertTrue("already installed: grok" in chrome.toString(), chrome.toString())
        }

        @Test
        fun `console preticks profiles whose credential is already on disk`(@TempDir home: Path) {
            var initial = emptySet<String>()
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            hasConsole = { true },
                            pickHeads = HeadPicker { _, selected ->
                                initial = selected
                                MultiSelectOutcome.Chosen(emptyList())
                            },
                        ),
                        loginHead = NO_REAL_LOGIN,
                        detect = { emptyFacts().copy(spliceOwned = setOf("chatgpt-oauth", "muse-oauth")) },
                        addProfile = ProfileAdd { error("empty tick must not add") },
                    ).setup()
                }
            }
            assertTrue("codex" in initial, initial.toString())
            assertTrue("muse" in initial, initial.toString())
            assertFalse("grok" in initial, initial.toString())
        }

        @Test
        fun `headless pretick is empty even when credentials exist`(@TempDir home: Path) {
            var initial = setOf("sentinel")
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            hasConsole = { false },
                            pickHeads = HeadPicker { _, selected ->
                                initial = selected
                                MultiSelectOutcome.Chosen(emptyList())
                            },
                        ),
                        loginHead = NO_REAL_LOGIN,
                        detect = { emptyFacts().copy(spliceOwned = setOf("chatgpt-oauth")) },
                        addProfile = ProfileAdd { error("empty tick must not add") },
                    ).setup()
                }
            }
            assertEquals(emptySet<String>(), initial)
        }

        @Test
        fun `ticked api-key profiles go through the AddCommand seam`(@TempDir home: Path) {
            val added = mutableListOf<String>()
            val wanted = AddProfiles().catalog().map { it.name }.filter { it == "deepseek" || it == "openrouter" }
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            pickHeads = HeadPicker { _, _ -> MultiSelectOutcome.Chosen(wanted) },
                        ),
                        loginHead = NO_REAL_LOGIN,
                        addProfile = ProfileAdd { name ->
                            added += name
                            true
                        },
                        restart = DaemonRestart { true },
                        detect = { emptyFacts() },
                    ).setup()
                }
            }
            assertEquals(wanted, added)
            assertTrue("deepseek" in added, added.toString())
        }
    }

    private fun summaryFor(home: Path, pick: SetupStart): String {
        val chrome = StringBuilder()
        withHome(home) {
            seedShim(home)
            runBlocking {
                SetupCommand(
                    prompts = SetupPrompts(
                        frame = WizardFrame(out = chrome, ask = ConfirmPrompt { _, _ -> false }),
                        choose = { _, _ -> SelectOutcome.Chosen(pick) },
                        spinner = Spinner(StringBuilder(), tty = false),
                    ),
                    loginHead = NO_REAL_LOGIN,
                    detect = { emptyFacts() },
                ).setup()
            }
        }
        return chrome.toString()
    }

    private fun emptyFacts() = SetupFacts(
        spliceOwned = emptySet(),
        vendorCli = emptySet(),
        openRouterKey = false,
        daemonUp = false,
        suggested = SetupStart.OpenRouter,
    )

    private fun seedShim(home: Path) {
        val share = home.resolve(".local").resolve("share").resolve("splice")
        Files.createDirectories(share)
        Files.writeString(share.resolve("splice-launch"), "#!/usr/bin/env bash\n")
    }

    private fun seedGrok(home: Path) {
        val cfg = home.resolve(".config").resolve("splice")
        Files.createDirectories(cfg)
        Files.writeString(
            cfg.resolve("splice.toml"),
            """
            [daemon]
            control_port = 3096
            [providers.grok]
            dialect = "openai-responses"
            base_url = "https://x"
            auth = { kind = "grok-oauth" }
            [heads.grok]
            provider = "grok"
            port = 3098
            discovery_prefix = "claude-grok--"
            pinned_model = "grok-4.6"
            [heads.grok.claude]
            command = "claude-grok"
            """.trimIndent() + "\n",
        )
    }

    private fun seedOauthTopology(home: Path) {
        val cfg = home.resolve(".config").resolve("splice")
        Files.createDirectories(cfg)
        Files.writeString(
            cfg.resolve("splice.toml"),
            """
            [daemon]
            control_port = 3096
            [providers.codex]
            dialect = "openai-responses"
            base_url = "https://x"
            auth = { kind = "chatgpt-oauth" }
            [heads.claudex]
            provider = "codex"
            port = 3099
            discovery_prefix = "claude-codex--"
            pinned_model = "gpt-5.6-sol"
            [heads.claudex.claude]
            command = "claudex"
            """.trimIndent() + "\n",
        )
    }

    private fun <T> withHome(home: Path, block: () -> T): T {
        val prev = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        return try {
            block()
        } finally {
            System.setProperty("user.home", prev)
        }
    }

    private fun captureStdout(block: () -> Unit): String {
        val buf = ByteArrayOutputStream()
        val prev = System.out
        System.setOut(PrintStream(buf, true))
        return try {
            block()
            buf.toString()
        } finally {
            System.setOut(prev)
        }
    }
}

/** The wizard's sign-in step. Left at its default, it runs a REAL OAuth login: on 2026-09-16 that
 *  opened accounts.x.ai in the operator's browser on every `:app:test` and then blocked in
 *  awaitCode for a callback that could never arrive, which read for a day as the daemon demanding a
 *  sign-in. Every construction here passes this instead, and LoginIo's wall now fails any test that
 *  forgets. */
private val NO_REAL_LOGIN = HeadSignIn { true }

private val HEADLESS_ORACLE = """
[daemon]
control_port = 3096
# Reasoning display (edit + restart; env/PATCH still override):
show_reasoning = "text"
summary = "detailed"
replay_reasoning = false

# Supported starter route: create an OpenRouter API key, then EITHER export OPENROUTER_API_KEY
# or let `claude-openrouter login` store it to ~/.config/splice/keys.toml (0600 — survives restarts from
# any shell; inside a claude-openrouter session you can also paste it as a bare message and the
# token-capture hook stores it without it reaching the model).
# Experimental vendor-OAuth examples remain opt-in in app/src/main/resources/splice.example.toml.
[providers.openrouter]
dialect = "openai-chat"
base_url = "https://openrouter.ai/api/v1"
auth = { kind = "api-key", env = "OPENROUTER_API_KEY" }

[[providers.openrouter.models]]
id = "anthropic/claude-sonnet-5"
label = "Claude Sonnet 5"
context_window = 1000000
[[providers.openrouter.models]]
id = "anthropic/claude-opus-5"
label = "Claude Opus 5"
context_window = 1000000
[[providers.openrouter.models]]
id = "z-ai/glm-5.3-flash"
label = "GLM 5.3 Flash"
context_window = 1310720
[[providers.openrouter.models]]
id = "openai/gpt-5.6-sol"
label = "GPT-5.6 Sol"
context_window = 1050000
[[providers.openrouter.models]]
id = "openai/gpt-5.6-luna"
label = "GPT-5.6 Luna"
context_window = 1050000
[[providers.openrouter.models]]
id = "google/gemini-3.8-flash"
label = "Gemini 3.8 Flash"
context_window = 1048576
[[providers.openrouter.models]]
id = "deepseek/deepseek-v4-flash-0731"
label = "DeepSeek V4 Flash 0731"
context_window = 1310720
[[providers.openrouter.models]]
id = "z-ai/glm-5.3"
label = "GLM 5.3"
context_window = 1310720
[[providers.openrouter.models]]
id = "meta-llama/llama-4-maverick"
label = "Llama 4 Maverick"
context_window = 1048576
[[providers.openrouter.models]]
id = "anthropic/claude-haiku-4.5"
label = "Claude Haiku 4.5"
context_window = 200000

[heads.openrouter]
provider = "openrouter"
port = 3101
discovery_prefix = "claude-openrouter--"
pinned_model = "anthropic/claude-sonnet-5"
models = [
  { id = "anthropic/claude-sonnet-5", slot = "sonnet" },
  { id = "anthropic/claude-opus-5", slot = "opus" },
  { id = "z-ai/glm-5.3-flash", slot = "haiku" },
  { id = "openai/gpt-5.6-sol", slot = "fable" },
]

[heads.openrouter.claude]
command = "claude-openrouter"
""".trimIndent() + "\n"
