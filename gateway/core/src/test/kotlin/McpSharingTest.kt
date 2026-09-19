import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.launch.DirectoryProbe
import splice.core.launch.McpServerSpec
import splice.core.launch.McpSharing

class McpSharingTest {

    private val global = Json.parseToJsonElement(
        """
        {
          "exa": {"command": "npx", "args": ["-y", "exa-mcp"], "env": {"EXA_API_KEY": "k"}},
          "docs": {"type": "http", "url": "https://code.claude.com/docs/mcp"},
          "fs": {"command": "npx", "args": ["-y", "server-filesystem", "/home/op/project"]},
          "expanded": {"command": "node", "args": ["${'$'}{HOME}/srv.js"]},
          "scoped": {"command": "node", "args": ["srv.js"], "cwd": "/home/op/project"},
          "banned": {"command": "node", "args": ["banned.js"]},
          "nocmd": {"args": ["x"]},
          "junk": 3
        }
        """.trimIndent(),
    ).jsonObject

    private fun sharing(enabled: Boolean = true, exclude: Set<String> = setOf("banned")) = McpSharing(
        enabled = enabled,
        exclude = exclude,
        endpointPrefix = "http://127.0.0.1:3096/mcp/",
        bearer = { "KEY" },
        isDirectory = DirectoryProbe { it == "/home/op/project" },
    )

    @Test
    fun `only a plain stdio entry is hosted and every rejection names its reason`() {
        val plan = sharing().plan(global)
        assertEquals(
            mapOf("exa" to McpServerSpec("exa", "npx", listOf("-y", "exa-mcp"), mapOf("EXA_API_KEY" to "k"))),
            plan.hosted,
        )
        assertEquals(
            setOf("docs", "fs", "expanded", "scoped", "banned", "nocmd", "junk"),
            plan.passthrough.keys,
        )
        assertTrue(plan.passthrough.getValue("docs").contains("http"))
        assertTrue(plan.passthrough.getValue("fs").contains("/home/op/project"))
        assertTrue(plan.passthrough.getValue("expanded").contains("VAR"))
        assertTrue(plan.passthrough.getValue("scoped").contains("cwd"))
        assertTrue(plan.passthrough.getValue("banned").contains("exclude"))
        assertEquals("no command", plan.passthrough.getValue("nocmd"))
        assertEquals("entry is not an object", plan.passthrough.getValue("junk"))
    }

    @Test
    fun `a command that expands, a relative path, or a directory behind a flag is project-scoped`() {
        val entries = Json.parseToJsonElement(
            """
            {
              "cmd": {"command": "${'$'}{HOME}/bin/mcp", "args": []},
              "dot": {"command": "node", "args": ["srv.js", "."]},
              "rel": {"command": "node", "args": ["./repo"]},
              "flag": {"command": "node", "args": ["--root=/home/op/project"]},
              "envdir": {"command": "node", "args": [], "env": {"ROOT": "/home/op/project"}},
              "fine": {"command": "node", "args": ["--port=8080", "/home/op/not-a-dir"]}
            }
            """.trimIndent(),
        ).jsonObject
        val plan = sharing().plan(entries)
        assertEquals(setOf("fine"), plan.hosted.keys)
        assertTrue(plan.passthrough.getValue("cmd").contains("VAR"))
        assertTrue(plan.passthrough.getValue("dot").contains("relative"))
        assertTrue(plan.passthrough.getValue("rel").contains("./repo"))
        assertTrue(plan.passthrough.getValue("flag").contains("/home/op/project"))
        assertTrue(plan.passthrough.getValue("envdir").contains("/home/op/project"))
        assertTrue(sharing().enabled)
        assertTrue(!sharing(enabled = false).enabled)
    }

    @Test
    fun `a hosted entry becomes an http entry at the host with the bearer, others are byte-identical`() {
        val out = sharing().plan(global).rewritten
        val exa = out.getValue("exa").jsonObject
        assertEquals("http", exa.getValue("type").jsonPrimitive.content)
        assertEquals("http://127.0.0.1:3096/mcp/exa", exa.getValue("url").jsonPrimitive.content)
        assertEquals("Bearer KEY", exa.getValue("headers").jsonObject.getValue("Authorization").jsonPrimitive.content)
        for (name in global.keys - "exa") assertEquals(global[name], out[name], name)
        assertEquals(global.keys.toList(), out.keys.toList(), "order preserved")
    }

    @Test
    fun `malformed launch fields pass through whole instead of changing the launch tuple`() {
        val entries = Json.parseToJsonElement(
            """{"argsType":{"command":"srv","args":"--stdio"},
                "argsMember":{"command":"srv","args":["--port",123]},
                "envType":{"command":"srv","env":[]},
                "envMember":{"command":"srv","env":{"PORT":123}},
                "transport":{"command":"srv","type":false},
                "nullArgs":{"command":"srv","args":null}}""",
        ).jsonObject
        val plan = sharing().plan(entries)
        assertTrue(plan.hosted.isEmpty(), plan.hosted.toString())
        assertEquals(entries, plan.rewritten)
        assertEquals(entries.keys, plan.passthrough.keys)
        assertTrue(plan.passthrough.values.all { it.contains("malformed") })
    }

    @Test
    fun `disabled hosting returns the operator's object untouched and hosts nothing`() {
        val plan = sharing(enabled = false).plan(global)
        assertEquals(global, plan.rewritten)
        assertTrue(plan.hosted.isEmpty())
        assertEquals("hosting disabled", plan.passthrough.getValue("exa"))
        assertNull(sharing(enabled = false).hostedSpec(global, "exa"))
    }

    @Test
    fun `the host resolves a name to the spec the operator's file declares now`() {
        assertEquals("npx", sharing().hostedSpec(global, "exa")?.command)
        assertNull(sharing().hostedSpec(global, "docs"))
        assertNull(sharing().hostedSpec(global, "missing"))
    }

    @Test
    fun `a relative path behind a flag and a bare directory name are project-scoped too`() {
        val entries = Json.parseToJsonElement(
            """{"flagrel":{"command":"srv","args":["--root=./repo"]},
                "bare":{"command":"srv","args":["repo"]},
                "flagword":{"command":"srv","args":["--mode=repo"]},
                "plain":{"command":"srv","args":["--stdio"]}}""",
        ).jsonObject
        // The probe answers for "repo" as the DAEMON's cwd would; review 3: that is the wrong cwd
        // for a relative name, so a bare word is never probed and only shape decides.
        val plan = McpSharing(true, emptySet(), "http://127.0.0.1:1/mcp/", { "K" }, DirectoryProbe { it == "repo" })
            .plan(entries)
        assertEquals(setOf("bare", "flagword", "plain"), plan.hosted.keys)
        assertTrue(plan.passthrough.getValue("flagrel").contains("./repo"))
    }

    @Test
    fun `relative shapes are refused without any cwd, npm scopes and urls are not paths`() {
        val entries = Json.parseToJsonElement(
            """{"rootword":{"command":"srv","args":["--root=repo"]},
                "slash":{"command":"srv","args":["src/server.js"]},
                "dir":{"command":"srv","args":["--dir","x"]},
                "rootpair":{"command":"srv","args":["--root","repo"]},
                "scoped":{"command":"npx","args":["-y","@scope/pkg"]},
                "url":{"command":"srv","args":["--url=https://x.example/api"]},
                "abs":{"command":"node","args":["/opt/srv/index.js"]}}""",
        ).jsonObject
        val plan = McpSharing(true, emptySet(), "http://127.0.0.1:1/mcp/", { "K" }, DirectoryProbe { false })
            .plan(entries)
        assertEquals(setOf("scoped", "url", "abs"), plan.hosted.keys)
        assertTrue(plan.passthrough.getValue("rootword").contains("--root=repo"))
        assertTrue(plan.passthrough.getValue("rootpair").contains("--root repo"))
        assertTrue(plan.passthrough.getValue("dir").contains("--dir x"))
        assertTrue(plan.passthrough.getValue("slash").contains("src/server.js"))
    }
}
