// The red/green proof for TestDiscovery.kt — one JUnit test per arm of the original bun
// checker's `--selftest`, plus proofs for behavior this port adds or changes:
//   - the JUnit XML reader now dies loudly on a root missing name/tests (DOM, not a stem
//     fallback — see parseJUnitXml's own comment);
//   - census() and summaryLine(), which did not exist as standalone functions before;
//   - the scanner's own measured traps (a backtick name holding an apostrophe, a nested class,
//     a body-less declaration at column 0) get direct proofs here instead of living only as a
//     comment someone has to trust.
// Everything runs against in-memory fixture strings except the "boring case", which exercises
// the File-based wrappers (scanModuleSources/scanModuleXml) through @TempDir — the one place
// splice.test-discovery.gradle.kts's own I/O boundary gets proved.
package splice.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.discovery.JUnitXml.parseJUnitXml
import splice.discovery.JUnitXml.scanModuleXml
import splice.discovery.JUnitXml.xmlRowFrom
import splice.discovery.SourceScan.classesIn
import splice.discovery.SourceScan.scanModuleSources
import splice.discovery.TestDiscovery.audit
import splice.discovery.TestDiscovery.census
import splice.discovery.TestDiscovery.summaryLine
import java.io.File

private const val MODULE = ":daemon-head"
private const val PATH = "SampleTest.kt"

private val SOURCE_OK = """
package head

import org.junit.jupiter.api.Test

class SampleTest {
    @Test
    fun `a discovered test`() = runBlocking { Unit }

    @Test
    fun `a second discovered test`() {
        assertEquals(1, 1)
    }

    private fun helper() = "not a test"
}
""".trimIndent()

// The measured shape: the fourth method's body returns a value, so JUnit skips it. The source is
// IDENTICAL in intent to SOURCE_OK — only the second method's body changes — which is the point:
// a checker reasoning from syntax alone cannot tell these two classes apart; only the XML can.
private val SOURCE_UNDISCOVERED = """
package head

import org.junit.jupiter.api.Test

class SampleTest {
    @Test
    fun `a discovered test`() = runBlocking { Unit }

    @Test
    fun `an undiscovered test`() = runBlocking { held.await() }
}
""".trimIndent()

private val SOURCE_EMPTY = """
package head

class NoTestsHere {
    private fun helper() = 1
}
""".trimIndent()

private const val XML_OK = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="SampleTest" tests="2" skipped="0" failures="0" errors="0">
  <testcase name="a discovered test()" classname="head.SampleTest"/>
  <testcase name="a second discovered test()" classname="head.SampleTest"/>
</testsuite>
"""

private const val XML_SHORT = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="SampleTest" tests="1" skipped="0" failures="0" errors="0">
  <testcase name="a discovered test()" classname="head.SampleTest"/>
</testsuite>
"""

private const val XML_HIGHER = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="SampleTest" tests="4" skipped="0" failures="0" errors="0">
  <testcase name="a discovered test()" classname="head.SampleTest"/>
  <testcase name="a second discovered test()" classname="head.SampleTest"/>
  <testcase name="a second discovered test()[1]" classname="head.SampleTest"/>
  <testcase name="a second discovered test()[2]" classname="head.SampleTest"/>
</testsuite>
"""

// THE THIRD SHAPE (TestDiscovery.kt's header, SHAPES): a @TestFactory declares ONE method and runs
// one child per row of a list that is EXPECTED to grow, so its expansion is not a number anyone
// could write down and re-earn. Modelled on the real ReleaseReadinessLawTest, which ran 49 children
// the day the rule was decided and 56 two days later, for the healthiest possible reason.
private val SOURCE_FACTORY = """
package head

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class FactoryTest {
    @Test
    fun `a plain test`() {
        check(true)
    }

    @TestFactory
    fun `one child per mutation`() = mutations.map { dynamicTest(it.name) { check(true) } }
}
""".trimIndent()

private const val XML_FACTORY_HIGHER = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="FactoryTest" tests="7" skipped="0" failures="0" errors="0">
  <testcase name="a plain test()" classname="head.FactoryTest"/>
  <testcase name="one child per mutation()[1]" classname="head.FactoryTest"/>
  <testcase name="one child per mutation()[2]" classname="head.FactoryTest"/>
  <testcase name="one child per mutation()[3]" classname="head.FactoryTest"/>
  <testcase name="one child per mutation()[4]" classname="head.FactoryTest"/>
  <testcase name="one child per mutation()[5]" classname="head.FactoryTest"/>
  <testcase name="one child per mutation()[6]" classname="head.FactoryTest"/>
</testsuite>
"""

// The factory method itself never ran: one plain test, and nothing the factory was supposed to
// expand into. This is the hazard the wall exists for, and no annotation exempts it.
private const val XML_FACTORY_SHORT = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="FactoryTest" tests="1" skipped="0" failures="0" errors="0">
  <testcase name="a plain test()" classname="head.FactoryTest"/>
</testsuite>
"""

class TestDiscoveryTest {

    private fun classes(source: String): List<TestClass> = classesIn(source, MODULE, PATH)
    private fun row(xml: String): Map<String, XmlRow> = mapOf(xmlRowFrom(xml, "fixture.xml"))

    // ── one test per --selftest arm of the original checker ──

    @Test
    fun `a compliant tree is green`() {
        val problems = audit(classes(SOURCE_OK), mapOf(MODULE to row(XML_OK)))
        assertTrue(problems.isEmpty(), "expected green, got: $problems")
    }

    @Test
    fun `THE MEASURED BUG - a short XML is red and names the method that never ran`() {
        val problems = audit(classes(SOURCE_UNDISCOVERED), mapOf(MODULE to row(XML_SHORT)))
        assertTrue(problems.any { "NOT DISCOVERED" in it }, "expected a NOT DISCOVERED problem, got: $problems")
        assertTrue(
            problems.any { "an undiscovered test" in it },
            "expected the missing method named, got: $problems",
        )
    }

    @Test
    fun `an undispositioned higher count is red`() {
        val problems = audit(classes(SOURCE_OK), mapOf(MODULE to row(XML_HIGHER)))
        assertTrue(problems.any { "HIGHER COUNT" in it }, "expected a HIGHER COUNT problem, got: $problems")
    }

    @Test
    fun `a blank disposition reason is red`() {
        val dispositions = mapOf("SampleTest" to Disposition("", 4))
        val problems = audit(classes(SOURCE_OK), mapOf(MODULE to row(XML_HIGHER)), dispositions = dispositions)
        assertTrue(problems.any { "NO reason" in it }, "expected a NO-reason problem, got: $problems")
    }

    @Test
    fun `a disposition whose count moved is stale`() {
        val dispositions = mapOf("SampleTest" to Disposition("one @ParameterizedTest expands to two cases", 3))
        val problems = audit(classes(SOURCE_OK), mapOf(MODULE to row(XML_HIGHER)), dispositions = dispositions)
        assertTrue(problems.any { "stale" in it }, "expected a stale-disposition problem, got: $problems")
    }

    @Test
    fun `a reasoned higher count matching its earned count is green`() {
        val dispositions =
            mapOf("SampleTest" to Disposition("one @ParameterizedTest expands to two extra cases", 4))
        val problems = audit(classes(SOURCE_OK), mapOf(MODULE to row(XML_HIGHER)), dispositions = dispositions)
        assertTrue(problems.isEmpty(), "expected green, got: $problems")
    }

    // ── THE THIRD SHAPE: @TestFactory, whose expansion no disposition could pin ──

    @Test
    fun `a @TestFactory class needs no disposition for a count above its declared one`() {
        val problems = audit(classes(SOURCE_FACTORY), mapOf(MODULE to row(XML_FACTORY_HIGHER)))
        assertTrue(problems.isEmpty(), "expected green, got: $problems")
    }

    /** The vacuity guard on the arm above: the exemption is the FACTORY's, not every class's. The
     *  identical XML against a class whose methods are all plain @Test is still red. */
    @Test
    fun `the same higher count without a factory is still red`() {
        val plain = SOURCE_FACTORY.replace("@TestFactory", "@Test")
        val problems = audit(classes(plain), mapOf(MODULE to row(XML_FACTORY_HIGHER)))
        assertTrue(problems.any { "HIGHER COUNT" in it }, "expected a HIGHER COUNT problem, got: $problems")
    }

    @Test
    fun `a @TestFactory class whose XML is SHORT is red and names the factory method`() {
        val problems = audit(classes(SOURCE_FACTORY), mapOf(MODULE to row(XML_FACTORY_SHORT)))
        assertTrue(problems.any { "NOT DISCOVERED" in it }, "expected a NOT DISCOVERED problem, got: $problems")
        assertTrue(
            problems.any { "one child per mutation" in it },
            "the factory method that never ran must be named, got: $problems",
        )
    }

    @Test
    fun `the scanner reports WHICH method the factory annotation marked`() {
        val parsed = classes(SOURCE_FACTORY).single()
        assertEquals(listOf("a plain test", "one child per mutation"), parsed.methods)
        assertEquals(setOf("one child per mutation"), parsed.dynamicMethods)
    }

    @Test
    fun `the census marks a factory class DYNAMIC rather than HIGHER-NO-REASON`() {
        val text = census(classes(SOURCE_FACTORY), mapOf(MODULE to row(XML_FACTORY_HIGHER)))
        assertTrue("DYNAMIC" in text, text)
        assertTrue("HIGHER-NO-REASON" !in text, text)
    }

    @Test
    fun `a module with tests and no XML at all is red by module name`() {
        val quiet = classesIn(SOURCE_OK.replace("SampleTest", "QuietTest"), ":quiet-module", "QuietTest.kt")
        val problems = audit(classes(SOURCE_OK) + quiet, mapOf(MODULE to row(XML_OK)))
        assertTrue(
            problems.any { ":quiet-module" in it && "NO XML at all" in it },
            "expected a NO-XML-at-all problem naming :quiet-module, got: $problems",
        )
    }

    @Test
    fun `a blank module disposition reason is red`() {
        val quiet = classesIn(SOURCE_OK.replace("SampleTest", "QuietTest"), ":quiet-module", "QuietTest.kt")
        val problems = audit(
            classes(SOURCE_OK) + quiet,
            mapOf(MODULE to row(XML_OK)),
            moduleDispositions = mapOf(":quiet-module" to ""),
        )
        assertTrue(problems.any { "NO reason" in it }, "expected a NO-reason problem, got: $problems")
    }

    @Test
    fun `a reasoned module disposition is green`() {
        val quiet = classesIn(SOURCE_OK.replace("SampleTest", "QuietTest"), ":quiet-module", "QuietTest.kt")
        val problems = audit(
            classes(SOURCE_OK) + quiet,
            mapOf(MODULE to row(XML_OK)),
            moduleDispositions = mapOf(":quiet-module" to "test task disabled by configuration unless -PrunX"),
        )
        assertTrue(problems.isEmpty(), "expected green, got: $problems")
    }

    @Test
    fun `zero parsed classes refuses to pass vacuously`() {
        val problems = audit(emptyList(), mapOf(MODULE to row(XML_OK)))
        assertTrue(
            problems.any { "refusing to pass vacuously" in it },
            "expected a vacuity-guard problem, got: $problems",
        )
    }

    @Test
    fun `no XML anywhere refuses to pass even with a real denominator`() {
        val problems = audit(classes(SOURCE_OK), emptyMap())
        assertTrue(problems.any { "no JUnit XML found" in it }, "expected a no-XML-anywhere problem, got: $problems")
    }

    // ── new in this port: the XML reader's die-loudly contract ──

    @Test
    fun `a JUnit XML root missing name dies loudly naming the file`() {
        val xml = """<?xml version="1.0"?><testsuite tests="1"><testcase name="x()"/></testsuite>"""
        val error = assertThrows<IllegalStateException> { parseJUnitXml(xml, "TEST-Weird.xml") }
        assertTrue(error.message!!.contains("TEST-Weird.xml"), "the file must be named in the failure")
    }

    @Test
    fun `a JUnit XML root missing tests dies loudly naming the file`() {
        val xml = """<?xml version="1.0"?><testsuite name="Weird"><testcase name="x()"/></testsuite>"""
        val error = assertThrows<IllegalStateException> { parseJUnitXml(xml, "TEST-Weird.xml") }
        assertTrue(error.message!!.contains("TEST-Weird.xml"), "the file must be named in the failure")
    }

    // ── new in this port: census() and summaryLine() ──

    @Test
    fun `census reports a stale XML row for a class no longer in source`() {
        val report = census(emptyList(), mapOf(MODULE to row(XML_OK)))
        assertTrue(report.contains("STALE-XML"), "expected a STALE-XML line, got:\n$report")
        assertTrue(report.contains("SampleTest"), "expected the stale class named, got:\n$report")
    }

    @Test
    fun `census marks a clean class OK and a short one SHORT`() {
        val okReport = census(classes(SOURCE_OK), mapOf(MODULE to row(XML_OK)))
        assertTrue(okReport.contains("OK"), "expected an OK mark, got:\n$okReport")
        val shortReport = census(classes(SOURCE_UNDISCOVERED), mapOf(MODULE to row(XML_SHORT)))
        assertTrue(shortReport.contains("SHORT"), "expected a SHORT mark, got:\n$shortReport")
    }

    @Test
    fun `summaryLine sums classes declared and observed`() {
        assertEquals(
            "tests-are-discovered: 1 class(es), 2 declared, 2 observed",
            summaryLine(classes(SOURCE_OK), mapOf(MODULE to row(XML_OK))),
        )
    }

    // ── the scanner's own measured traps, proved directly ──

    @Test
    fun `a nested class is qualified Outer dollar Inner, matching how JUnit names the row`() {
        val source = """
            package app

            import org.junit.jupiter.api.Test

            class SetupCommandTest {
                @Test
                fun `outer test`() {
                    assertTrue(true)
                }

                inner class Heads {
                    @Test
                    fun `inner test`() {
                        assertTrue(true)
                    }
                }
            }
        """.trimIndent()
        val found = classesIn(source, MODULE, PATH).associateBy { it.name }
        assertEquals(listOf("outer test"), found.getValue("SetupCommandTest").methods)
        assertEquals(listOf("inner test"), found.getValue("SetupCommandTest\$Heads").methods)
    }

    @Test
    fun `a backtick test name holding an apostrophe is preserved, not read as a character literal`() {
        val source = """
            package app

            import org.junit.jupiter.api.Test

            class ApostropheTest {
                @Test
                fun `the operator's servers stay isolated`() {
                    assertTrue(true)
                }
            }
        """.trimIndent()
        val found = classesIn(source, MODULE, PATH)
        assertEquals(1, found.size)
        assertEquals(listOf("the operator's servers stay isolated"), found.single().methods)
    }

    @Test
    fun `a body-less declaration at column 0 does not swallow the next class into its own span`() {
        val source = """
            package app

            import org.junit.jupiter.api.Test

            data class Topology(val daemon: String, val client: String)
            class SampleTest {
                @Test
                fun `a discovered test`() {
                    assertTrue(true)
                }
            }
        """.trimIndent()
        val found = classesIn(source, MODULE, PATH)
        assertEquals(1, found.size, "the body-less data class must not be read as holding SampleTest's tests")
        assertEquals("SampleTest", found.single().name)
        assertEquals(listOf("a discovered test"), found.single().methods)
    }

    // ── the boring case: the File-based wrappers, through @TempDir ──

    @Test
    fun `the boring case - one class one Test method, discovered is green and undiscovered is red by name`(
        @TempDir temp: File,
    ) {
        val sourceDir = File(temp, "src/test/kotlin").apply { mkdirs() }
        File(sourceDir, "OneMethodTest.kt").writeText(
            """
            package boring

            import org.junit.jupiter.api.Test

            class OneMethodTest {
                @Test
                fun `the only test`() {
                    assertEquals(1, 1)
                }
            }
            """.trimIndent(),
        )
        val xmlDir = File(temp, "build/test-results/test").apply { mkdirs() }
        val xmlFile = File(xmlDir, "TEST-boring.OneMethodTest.xml")
        val discoveredClasses = scanModuleSources(sourceDir, ":boring")

        xmlFile.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="boring.OneMethodTest" tests="1" skipped="0" failures="0" errors="0">
              <testcase name="the only test()" classname="boring.OneMethodTest"/>
            </testsuite>
            """.trimIndent(),
        )
        var problems = audit(discoveredClasses, mapOf(":boring" to scanModuleXml(listOf(xmlDir))))
        assertTrue(problems.isEmpty(), "one declared, one observed must be green, got: $problems")

        xmlFile.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="boring.OneMethodTest" tests="0" skipped="0" failures="0" errors="0">
            </testsuite>
            """.trimIndent(),
        )
        problems = audit(discoveredClasses, mapOf(":boring" to scanModuleXml(listOf(xmlDir))))
        assertTrue(
            problems.any { "NOT DISCOVERED" in it && "the only test" in it },
            "one declared, zero observed must be red by method name, got: $problems",
        )
    }

    // ── the multi-producer merge (:app's codeModePackagedTest shape: two Test tasks, two result
    //    directories, one overlapping class) — through real directories, the same shape
    //    scanModuleXml's own caller (splice.test-discovery.gradle.kts) hands it. ──

    @Test
    fun `two producers for the same class keep the lower count and the union of names`(@TempDir temp: File) {
        val fullRun = File(temp, "test").apply { mkdirs() }
        File(fullRun, "TEST-head.SampleTest.xml").writeText(XML_OK)
        val packagedRerun = File(temp, "codeModePackagedTest").apply { mkdirs() }
        File(packagedRerun, "TEST-head.SampleTest.xml").writeText(
            """<?xml version="1.0"?>
            <testsuite name="SampleTest" tests="1"><testcase name="a discovered test()"/></testsuite>
            """.trimIndent(),
        )

        val merged = scanModuleXml(listOf(fullRun, packagedRerun))
        val sampleRow = merged.getValue("SampleTest")
        assertEquals(1, sampleRow.count, "the lower of the two producers' counts must win")
        assertEquals(setOf("a discovered test", "a second discovered test"), sampleRow.names)
    }
}
