package com.torad.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.architecture.architecture
import com.lemonappdev.konsist.api.ext.list.withAllParentsOf
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.ext.list.withNameContaining
import com.lemonappdev.konsist.api.verify.assertTrue
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

/**
 * Torad Architecture Tests
 *
 * These tests enforce architectural boundaries via Konsist.
 * Customize the package patterns to match your project structure.
 *
 * Install:
 * 1. Add konsist dependency: testImplementation("com.lemonappdev:konsist:0.17.0")
 * 2. Copy this file to src/test/kotlin/com/yourproject/konsist/
 * 3. Update package patterns to match your project
 */
class ArchitectureTest {

    // === LAYER DEFINITIONS ===
    // Customize these package patterns for your project

    private val architecture = architecture {
        val domain = Layer("Domain", "..domain..")
        val data = Layer("Data", "..data..")
        val presentation = Layer("Presentation", "..presentation..")
        val ui = Layer("UI", "..ui..")

        // Domain is independent — depends on nothing
        domain.dependsOnNothing()

        // Data depends only on Domain
        data.dependsOn(domain)

        // Presentation depends on Domain (not Data directly)
        presentation.dependsOn(domain)

        // UI depends on Presentation
        ui.dependsOn(presentation)
    }

    @Test
    fun `architecture layers have correct dependencies`() {
        Konsist
            .scopeFromProject()
            .assertArchitecture(architecture)
    }

    // === NAMING CONVENTIONS ===

    @Test
    fun `classes with UseCase suffix should reside in domain or usecase package`() {
        Konsist
            .scopeFromProject()
            .classes()
            .withNameEndingWith("UseCase")
            .assertTrue { it.resideInPackage("..domain..") || it.resideInPackage("..usecase..") }
    }

    @Test
    fun `classes with Repository suffix should reside in data package`() {
        Konsist
            .scopeFromProject()
            .classes()
            .withNameEndingWith("Repository", "RepositoryImpl")
            .assertTrue { it.resideInPackage("..data..") || it.resideInPackage("..repository..") }
    }

    @Test
    fun `classes with ViewModel suffix should reside in presentation or ui package`() {
        Konsist
            .scopeFromProject()
            .classes()
            .withNameEndingWith("ViewModel")
            .assertTrue {
                it.resideInPackage("..presentation..") ||
                it.resideInPackage("..ui..") ||
                it.resideInPackage("..viewmodel..")
            }
    }

    @Test
    fun `classes with UiState suffix should be data classes`() {
        Konsist
            .scopeFromProject()
            .classes()
            .withNameEndingWith("UiState", "State")
            .assertTrue { it.hasDataModifier }
    }

    // === USECASE STRUCTURE ===

    @Test
    fun `UseCases should have single public operator invoke method`() {
        Konsist
            .scopeFromProject()
            .classes()
            .withNameEndingWith("UseCase")
            .assertTrue { klass ->
                val publicFunctions = klass.functions()
                    .filter { it.hasPublicOrDefaultModifier }
                    .filterNot { it.hasOverrideModifier }

                publicFunctions.count() == 1 &&
                    publicFunctions.first().name == "invoke" &&
                    publicFunctions.first().hasOperatorModifier
            }
    }

    @Test
    fun `UseCases should only depend on other UseCases or Repositories`() {
        Konsist
            .scopeFromProject()
            .classes()
            .withNameEndingWith("UseCase")
            .assertTrue { klass ->
                val constructorParams = klass.primaryConstructor?.parameters ?: emptyList()
                constructorParams.all { param ->
                    val typeName = param.type.name
                    typeName.endsWith("UseCase") ||
                    typeName.endsWith("Repository") ||
                    typeName.endsWith("DataSource") ||
                    typeName.endsWith("Dao") ||
                    typeName.contains("Dispatcher") ||
                    typeName.contains("Clock") ||
                    typeName.contains("Json") ||
                    typeName.contains("Scope")
                }
            }
    }

    // === REPOSITORY STRUCTURE ===

    @Test
    fun `Repository interfaces should have matching Impl classes`() {
        Konsist
            .scopeFromProject()
            .interfaces()
            .withNameEndingWith("Repository")
            .assertTrue { iface ->
                val implName = "${iface.name}Impl"
                Konsist.scopeFromProject()
                    .classes()
                    .any { it.name == implName }
            }
    }

    @Test
    fun `Repositories should not depend on UseCases or ViewModels`() {
        Konsist
            .scopeFromProject()
            .classes()
            .withNameEndingWith("Repository", "RepositoryImpl")
            .assertTrue { klass ->
                val constructorParams = klass.primaryConstructor?.parameters ?: emptyList()
                constructorParams.none { param ->
                    val typeName = param.type.name
                    typeName.endsWith("UseCase") ||
                    typeName.endsWith("ViewModel") ||
                    typeName.endsWith("UiState")
                }
            }
    }

    // === SEALED CLASS STRUCTURE ===

    @Test
    fun `sealed classes should be classes, not interfaces`() {
        Konsist
            .scopeFromProject()
            .interfaces()
            .filter { it.hasModifier(com.lemonappdev.konsist.api.declaration.modifier.KoModifier.SEALED) }
            .filterNot { it.hasAnnotationOf("kotlinx.serialization.Serializable") }
            .filterNot { it.hasPrivateModifier }
            .assertTrue(
                positiveCheck = { false },
                negativeCheck = { true }
            ) {
                // Test passes if no public sealed interfaces exist (besides Serializable ones)
                false
            }
    }

    @Test
    fun `sealed class variants should be nested inside the parent`() {
        Konsist
            .scopeFromProject()
            .classes()
            .filter { it.hasModifier(com.lemonappdev.konsist.api.declaration.modifier.KoModifier.SEALED) }
            .assertTrue { sealedClass ->
                // Check that the sealed class has nested classes/objects
                val nestedDeclarations = sealedClass.nestedClasses() + sealedClass.objects()
                nestedDeclarations.isNotEmpty()
            }
    }

    // === IMMUTABILITY ===

    @Test
    fun `UiState data classes should not have var properties`() {
        Konsist
            .scopeFromProject()
            .classes()
            .filter { it.hasDataModifier }
            .filter { it.name.endsWith("UiState") || it.name.endsWith("State") }
            .assertTrue { klass ->
                klass.properties().none { it.hasVarModifier }
            }
    }

    @Test
    fun `data classes should not have mutable collection properties`() {
        Konsist
            .scopeFromProject()
            .classes()
            .filter { it.hasDataModifier }
            .assertTrue { klass ->
                klass.properties().none { prop ->
                    val typeName = prop.type.name
                    typeName.startsWith("MutableList") ||
                    typeName.startsWith("MutableSet") ||
                    typeName.startsWith("MutableMap") ||
                    typeName.startsWith("ArrayList") ||
                    typeName.startsWith("HashSet") ||
                    typeName.startsWith("HashMap")
                }
            }
    }

    // === VIEWMODEL STRUCTURE ===

    @Test
    fun `ViewModels should not have private functions with complex logic`() {
        // Many private functions in ViewModel = logic should be in UseCases
        Konsist
            .scopeFromProject()
            .classes()
            .withNameEndingWith("ViewModel")
            .assertTrue { klass ->
                val privateFunctions = klass.functions().filter { it.hasPrivateModifier }
                // Allow up to 3 private helper functions
                privateFunctions.count() <= 3
            }
    }

    @Test
    fun `ViewModels should not directly depend on Repositories or DataSources`() {
        Konsist
            .scopeFromProject()
            .classes()
            .withNameEndingWith("ViewModel")
            .assertTrue { klass ->
                val constructorParams = klass.primaryConstructor?.parameters ?: emptyList()
                constructorParams.none { param ->
                    val typeName = param.type.name
                    typeName.endsWith("Repository") ||
                    typeName.endsWith("RepositoryImpl") ||
                    typeName.endsWith("DataSource") ||
                    typeName.endsWith("Dao")
                }
            }
    }

    // === ANDROID LAYER ISOLATION ===

    @Test
    fun `domain layer should not import Android packages`() {
        Konsist
            .scopeFromProject()
            .files
            .filter { it.resideInPackage("..domain..") }
            .assertTrue { file ->
                file.imports.none { import ->
                    import.name.startsWith("android.") ||
                    import.name.startsWith("androidx.")
                }
            }
    }

    @Test
    fun `data layer should not import presentation packages`() {
        Konsist
            .scopeFromProject()
            .files
            .filter { it.resideInPackage("..data..") }
            .assertTrue { file ->
                file.imports.none { import ->
                    import.name.contains(".presentation.") ||
                    import.name.contains(".ui.") ||
                    import.name.contains(".viewmodel.")
                }
            }
    }

    // === CONVERTER STRUCTURE ===

    @Test
    fun `Converters should be UseCase classes with operator invoke`() {
        Konsist
            .scopeFromProject()
            .classes()
            .withNameContaining("Converter", "ToUiState", "ToUiModel")
            .assertTrue { klass ->
                klass.hasFunction { fn ->
                    fn.name == "invoke" && fn.hasOperatorModifier
                }
            }
    }

    @Test
    fun `no top-level converter functions returning UiState`() {
        Konsist
            .scopeFromProject()
            .functions()
            .filter { it.isTopLevel }
            .filter { fn ->
                val returnType = fn.returnType?.name ?: ""
                returnType.endsWith("UiState") || returnType.endsWith("UiModel")
            }
            .assertTrue(
                positiveCheck = { false },
                negativeCheck = { true }
            ) {
                // Test passes if no top-level functions return UiState
                false
            }
    }

    // === EXTENSION FUNCTION BAN ===

    @Test
    fun `no project-defined extension functions`() {
        Konsist
            .scopeFromProject()
            .functions()
            .filter { it.isExtension }
            .filter { it.containingFile.path.contains("src/main") }
            .assertTrue(
                positiveCheck = { false },
                negativeCheck = { true }
            ) {
                // Test passes if no extension functions exist in main sources
                false
            }
    }

    // === FLOW PATTERNS ===

    @Test
    fun `Repository functions should return Flow, not be suspend`() {
        Konsist
            .scopeFromProject()
            .classes()
            .withNameEndingWith("Repository", "RepositoryImpl")
            .flatMap { it.functions() }
            .filter { it.hasPublicOrDefaultModifier }
            .filterNot { it.hasOverrideModifier }
            .assertTrue { fn ->
                // Either returns Flow or is not suspend
                val returnType = fn.returnType?.name ?: ""
                returnType.startsWith("Flow") || !fn.hasSuspendModifier
            }
    }
}
