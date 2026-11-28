package splice.app.mods

import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.mods.install.ModInstall
import java.nio.file.Files
import java.nio.file.Path

class ModImportRootTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `a mod installed before the host wrote its guard still cannot be aliased to code outside its folder`() =
        testApplication {
            val rig = RunnerTestRig(root)
            application { routing { rig.mount.register(this) } }
            val elsewhere = Files.createDirectories(rig.paths.modsDir.resolve("elsewhere"))
            Files.writeString(elsewhere.resolve("value.ts"), "export const value = 1;")
            Files.writeString(
                rig.paths.modsDir.resolve("tsconfig.json"),
                """{"compilerOptions":{"paths":{"@outside/*":["./elsewhere/*"]}}}""",
            )
            val source = rig.fixture(
                "aliased",
                """
                import { value } from "@outside/value";
                export function register(on) { on("mod.start", $ => $.log(String(value))); }
                """.trimIndent(),
            )
            rig.mods.install(ModInstall(source, false, rig.mods.bun.atInstall()?.toString()))
            rig.runner.start()
            try {
                val row = rig.eventually { rig.rows(client).single().takeIf { it.field("state") == "failed" } }
                assertTrue(row.field("why").contains("Cannot find package '@outside/value'"), row.field("why"))
                assertEquals("{}", Files.readString(rig.paths.modsDir.resolve("installed/tsconfig.json")).trim())
            } finally {
                rig.runner.stop()
            }
        }

    @Test
    fun `a mod that imports a file outside its folder fails to load and the person is told which file`() =
        testApplication {
            val rig = RunnerTestRig(root)
            application { routing { rig.mount.register(this) } }
            Files.writeString(root.resolve("outside.ts"), "export const value = 1;")
            val source = rig.fixture(
                "escaping",
                """
                import { value } from ${"\""}${root.resolve("outside.ts")}${"\""};
                export function register(on) { on("mod.start", $ => $.log(String(value))); }
                """.trimIndent(),
            )
            rig.runner.start()
            try {
                val row = rig.install(client, source)
                assertEquals("failed", row.field("state"))
                val why = row.field("why")
                assertTrue(why.contains("hooks/register.ts imports ${root.resolve("outside.ts")}"), why)
                assertTrue(why.contains("outside the mod's folder"), why)
            } finally {
                rig.runner.stop()
            }
        }

    private fun JsonObject.field(key: String): String = getValue(key).jsonPrimitive.content
}
