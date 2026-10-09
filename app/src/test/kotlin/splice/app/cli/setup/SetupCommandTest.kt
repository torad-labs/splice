// splice setup on the prompt toolkit. Headless topology is the
// captured first-run no-plan oracle; declining confirm writes nothing. Extra heads tick
// from AddProfiles and install through AddCommand.
package splice.app.cli.setup

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.configuration.add.AddProfiles
import splice.configuration.add.DaemonRestart
import splice.core.config.UserHome
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepth
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.terminal.ConfirmPrompt
import splice.terminal.MultiSelectOutcome
import splice.terminal.SelectOutcome
import splice.terminal.Spinner
import splice.terminal.WizardFrame
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class SetupCommandTest {

    @Test
    fun `headless setup writes the captured no-plan topology`(@TempDir home: Path) {
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
        assertTrue("splice status" in log, log)
        assertTrue("splice doctor" in log, log)
        assertFalse("splice dashboard" in log, "the console was removed on Oct 7, 2026: $log")
        assertTrue("anything wrong prints its fix" in log)
        // ONCE. The 2026-09-22 redesign gave this block its own "Setup complete." heading while
        // SetupCommand still ended on the frame's outro, so the last screen announced completion
        // twice — the old "You're set." heading had the same duplicate against "Toolkit ready!".
        // The frame owns the announcement; this block owns what to type.
        assertEquals(
            1,
            Regex("Not set up yet\\.").findAll(log).count(),
            "a planless setup must not claim completion: $log",
        )
        assertTrue("splice setup" in log, "the planless close names the command to connect a plan: $log")
        assertFalse("You're set." in log, "no head is ready until a plan is chosen: $log")
    }

    @Test
    fun `the close never calls a head ready that has no credential`(@TempDir tmp: Path) {
        // The redesign's hero is "a READY head where there is one", but readiness was OAuth-only:
        // an api-key head with no key counted as ready, so on a topology that lists it first the
        // wizard's largest word was a command that fails on launch. Readiness is now the predicate
        // `splice status` uses, and the fix for any head missing a credential is `<command> login`,
        // which the launch shim routes to LoginCommand for every kind, the api-key prompt included.
        val tokens = Files.writeString(tmp.resolve("kimi-auth.json"), "{}")
        val topology = Topology(
            providers = mapOf(
                "or" to ProviderConfig(
                    Dialect.OPENAI_CHAT,
                    "https://example.invalid",
                    AuthConfig("api-key", env = CLOSE_KEY),
                ),
                "kimi" to ProviderConfig(
                    Dialect.ANTHROPIC_PASSTHROUGH,
                    "https://example.invalid",
                    AuthConfig("kimi-oauth", file = tokens.toString()),
                ),
            ),
            // The keyless head FIRST, so a readiness check that misses it makes it the hero.
            heads = linkedMapOf(
                "openrouter" to closeHead("or", "claude-or"),
                "kimi" to closeHead("kimi", "claude-kimi"),
            ),
        )
        val log = captureStdout {
            SetupSignIn(NO_REAL_LOGIN, EnvReader { null }, CliPalette(ColorDepth.NONE)).printNextSteps(topology)
        }
        val hero = log.lines().first { it.startsWith("      ") && it.isNotBlank() }.trim()
        assertEquals("claude-kimi", hero, "the hero must be a head that can launch: $log")
        assertTrue("claude-or login" in log, "the keyless head must name the command that fixes it: $log")
        assertFalse(log.lines().any { "also ready" in it && "claude-or" in it }, "a keyless head is not ready: $log")
    }

    private fun closeHead(provider: String, command: String) = HeadConfig(
        provider = provider,
        port = 3099,
        discoveryPrefix = "test--",
        pinnedModel = "test-model",
        claude = ClaudeWrapperConfig(command = command),
    )

    // Operator ruling, 2026-09-26: no disclaimer anywhere. The walk says where the sign-in lands, nothing more.
    @Test
    fun `sign-in walk names its own credential file and prints no disclaimer`(@TempDir home: Path) {
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
        assertFalse(log.contains("unofficial", ignoreCase = true), log)
        assertFalse(log.contains("own risk", ignoreCase = true), log)
        assertTrue("own credential file" in log, log)
    }

    @Test
    fun `two different picks produce two different Summary bodies`(@TempDir home: Path) {
        val openRouter = summaryFor(home, SetupStart.OpenRouter)
        val oauth = summaryFor(home, SetupStart.OAuth("chatgpt-oauth"))
        assertTrue(openRouter != oauth, "discarding the pick would make both Summaries identical")
        assertTrue("Wrapper commands under" in openRouter, openRouter)
        assertTrue("Wrapper commands under" in oauth, oauth)
        assertTrue("splice add codex" in oauth, "the selected plan must name a real profile: $oauth")
        assertFalse("splice add codex" in openRouter, openRouter)
        assertFalse("splice add chatgpt-oauth" in oauth, "an auth kind is not an add profile: $oauth")
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
                        detect = { emptyFacts() },
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { error("empty tick must not add") },
                        ),
                    ).setup()
                }
            }
            val log = chrome.toString()
            val catalog = AddProfiles().catalog().map { it.name }.toSet()
            val named = catalog.filter { name -> "splice add $name" in log }.toSet()
            assertEquals(catalog, offered.toSet() + named, "catalog=$catalog offered=$offered named=$named log=$log")
            assertTrue("api-key" in named, log)
            assertFalse("api-key" in offered)
            assertTrue("local" in named, "a local model needs a name, URL and model before adding: $log")
            assertFalse("local" in offered, "the wizard cannot tick an incomplete local profile")
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
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { name ->
                                added += name
                                true
                            },
                            restart = DaemonRestart {
                                restarts += 1
                                true
                            },
                        ),
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
                        detect = { emptyFacts() },
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { name ->
                                added += name
                                name != "grok"
                            },
                            restart = DaemonRestart { true },
                        ),
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
                        detect = { emptyFacts() },
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { true },
                            restart = DaemonRestart {
                                restarts += 1
                                true
                            },
                        ),
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
                        detect = { emptyFacts() },
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { error("empty tick must not add") },
                        ),
                    ).setup()
                }
            }
            assertFalse("grok" in offered, offered.toString())
            assertTrue("already installed: grok" in chrome.toString(), chrome.toString())
        }

        @Test
        fun `console preticks profiles whose credential is already on disk`(@TempDir home: Path) {
            var initial = emptySet<String>()
            val prompts = SetupPrompts(
                hasConsole = { true },
                pickHeads = HeadPicker { _, selected ->
                    initial = selected
                    MultiSelectOutcome.Chosen(emptyList())
                },
            )
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = prompts,
                        loginHead = NO_REAL_LOGIN,
                        detect = { emptyFacts().copy(spliceOwned = setOf("chatgpt-oauth", "muse-oauth")) },
                        // A console run reaches the local-model offer; left at its default it would
                        // spawn the real nvidia-smi. No card here, so it is never asked.
                        localModel = SetupLocalModel(
                            prompts,
                            EnvReader { null },
                            DaemonRestart { true },
                            host = RigHost(gpu = GpuProbe { emptyList() }),
                        ),
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { error("empty tick must not add") },
                        ),
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
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { error("empty tick must not add") },
                        ),
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
                        detect = { emptyFacts() },
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { name ->
                                added += name
                                true
                            },
                            restart = DaemonRestart { true },
                        ),
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
        return UserHome.within(home) {
            block()
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

/** An env var no machine sets, so the close's readiness check cannot find a key for it. */
private const val CLOSE_KEY = "TEST_SETUP_CLOSE_KEY"

private val HEADLESS_ORACLE = """
[daemon]
control_port = 3096
# Reasoning display (edit + restart; env/PATCH still override):
show_reasoning = "text"
summary = "detailed"
replay_reasoning = false

# No provider or head is selected on first run. Connect a plan with `splice setup`, or add a
# specific profile with `splice add <profile>`. The ChatGPT, Grok, Kimi and Muse sign-in examples
# are in splice.example.toml.
""".trimIndent() + "\n"
