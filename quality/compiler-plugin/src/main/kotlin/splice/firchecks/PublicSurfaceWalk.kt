// NEW: (V4-92) the compiler's own answer to "which classes does a reachable signature name": the reading
// FirExposedVisibilityDeclarationChecker gives every declaration, collecting what it would test.
package splice.firchecks

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.EffectiveVisibility
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRefsOwner
import org.jetbrains.kotlin.fir.declarations.utils.effectiveVisibility
import org.jetbrains.kotlin.fir.declarations.utils.expandedConeType
import org.jetbrains.kotlin.fir.declarations.utils.isFromSealedClass
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirLocalPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeFlexibleType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.name.ClassId

/** What an INTERNAL class would be, measured against a declaration: the exposure checker's own reporting verdicts. */
private val EXPOSED = setOf(EffectiveVisibility.Permissiveness.LESS, EffectiveVisibility.Permissiveness.UNKNOWN)

/** One file's declarations, read the way FirExposedVisibilityDeclarationChecker reads them (2.3.21), into [ledger].
 *  Every class or alias it reads beyond the file is recorded through [lookups] as a lookup of that file. */
@OptIn(DirectDeclarationsAccess::class)
internal class PublicSurfaceWalk(
    private val session: FirSession,
    private val ledger: SurfaceLedger,
    lookups: SurfaceLookups,
) {
    private val register = TypeLookups(session, lookups)

    fun walk(file: FirFile) {
        file.declarations.forEach(::visit)
    }

    private fun visit(declaration: FirDeclaration) {
        when (declaration) {
            is FirRegularClass -> {
                regularClass(declaration)
                declaration.declarations.forEach(::visit)
            }
            is FirTypeAlias -> typeAlias(declaration)
            is FirAnonymousFunction, is FirPropertyAccessor -> Unit
            is FirFunction -> function(declaration)
            is FirProperty -> property(declaration)
            else -> Unit
        }
    }

    /** checkSupertypes: a class's CLASS supertype and an interface's INTERFACE supertypes; never a class's interfaces. */
    private fun regularClass(declaration: FirRegularClass) {
        val owner = ownerOf(declaration.symbol.classId)
        declareIfTopLevel(owner, !declaration.symbol.classId.isNestedClass, declaration.effectiveVisibility)
        if (!reachable(declaration.effectiveVisibility)) return
        val isInterface = declaration.classKind == ClassKind.INTERFACE
        val written = declaration.superTypeRefs.filter { it.source?.kind != KtFakeSourceElementKind.EnumSuperTypeRef }
        for (supertypeRef in written) {
            val supertype = supertypeRef.coneType
            if (supertype is ConeClassLikeType) register(supertype)
            val superClass = supertype.toRegularClassSymbol(session)
            if (superClass != null && (superClass.classKind == ClassKind.INTERFACE) == isInterface) {
                collect(supertype, owner)
            }
        }
        bounds(declaration, owner)
    }

    private fun typeAlias(declaration: FirTypeAlias) {
        val owner = ownerOf(declaration.symbol.classId)
        declareIfTopLevel(owner, !declaration.symbol.classId.isNestedClass, declaration.effectiveVisibility)
        if (!reachable(declaration.effectiveVisibility)) return
        declaration.expandedConeType?.let { collect(it, owner) }
        bounds(declaration, owner)
    }

    /** checkFunction: a sealed class's constructor is PrivateInClass whatever it is declared as, so it reaches nothing. */
    private fun function(declaration: FirFunction) {
        val owner = ownerOf(declaration) ?: return
        if (declaration.source?.kind is KtFakeSourceElementKind) return
        declareIfTopLevel(owner, declaration.symbol.callableId.classId == null, declaration.effectiveVisibility)
        val sealedConstructor = declaration is FirConstructor && declaration.isFromSealedClass
        if (sealedConstructor || !reachable(declaration.effectiveVisibility)) return
        declaration.valueParameters.forEach { collect(it.returnTypeRef.coneType, owner) }
        callable(declaration, owner, declaration !is FirConstructor)
    }

    /** checkProperty, constructor-declared properties included: the checker reaches those through the constructor
     *  parameter's correspondingProperty, at the PROPERTY's visibility, which is what this reads. */
    private fun property(declaration: FirProperty) {
        val owner = ownerOf(declaration) ?: return
        val generated = declaration.symbol is FirLocalPropertySymbol ||
            declaration.source?.kind == KtFakeSourceElementKind.EnumGeneratedDeclaration
        if (generated) return
        declareIfTopLevel(owner, declaration.symbol.callableId?.classId == null, declaration.effectiveVisibility)
        if (reachable(declaration.effectiveVisibility)) callable(declaration, owner, true)
    }

    /** What checkFunction and checkProperty share. A constructor has no return type or type parameters to check. */
    private fun callable(declaration: FirCallableDeclaration, owner: String, typed: Boolean) {
        if (typed) {
            collect(declaration.returnTypeRef.coneType, owner)
            bounds(declaration, owner)
        }
        declaration.receiverParameter?.typeRef?.let { collect(it.coneType, owner) }
        declaration.contextParameters.forEach { collect(it.returnTypeRef.coneType, owner) }
    }

    private fun bounds(declaration: FirTypeParameterRefsOwner, owner: String) {
        for (parameter in declaration.typeParameters) {
            parameter.symbol.resolvedBounds.forEach { collect(it.coneType, owner) }
        }
    }

    /** Would an INTERNAL class in this signature be an EXPOSED_* error? relationForExposedVisibility's own relation. */
    private fun reachable(visibility: EffectiveVisibility): Boolean =
        visibility != EffectiveVisibility.Local &&
            EffectiveVisibility.Internal.relation(visibility, session.typeContext) in EXPOSED

    private fun declareIfTopLevel(owner: String, topLevel: Boolean, visibility: EffectiveVisibility) {
        if (topLevel && visibility == EffectiveVisibility.Public) ledger.declare(owner)
    }

    /** findVisibilityExposure's walk: the fully expanded class, then the type's own arguments, star bounds included.
     *  EVERY class it names is recorded, whatever module declares it. Whether a class is this module's is a fact about
     *  the whole compilation, not about one source: an incremental compile reads the files it did not recompile as
     *  precompiled classes, never as sources. So the law keeps the targets in its own module's census, and a report
     *  stays a function of its source and the ABI of the classes it reads. Each of those is registered here, before it
     *  is read: the class or alias [type] names, every alias on the way to the class it expands to, and that class,
     *  whose type parameters' bounds a star argument reads. */
    private fun collect(type: ConeKotlinType, owner: String, visited: MutableSet<ConeKotlinType> = mutableSetOf()) {
        if (!visited.add(type)) return
        val classLike = when (type) {
            is ConeClassLikeType -> type
            is ConeFlexibleType -> type.lowerBound as? ConeClassLikeType ?: return
            else -> return
        }
        register(classLike)
        val target = classLike.fullyExpandedType(session).lookupTag.toSymbol(session)?.takeUnless { it.isLocal }
        if (target != null) ledger.reach(owner, ownerOf(target.classId))
        collectArguments(classLike, owner, visited)
    }

    private fun collectArguments(type: ConeClassLikeType, owner: String, visited: MutableSet<ConeKotlinType>) {
        for ((index, argument) in type.typeArguments.withIndex()) {
            when (argument) {
                is ConeClassLikeType -> collect(argument, owner, visited)
                is ConeKotlinTypeProjection -> collect(argument.type, owner, visited)
                is ConeStarProjection -> type.toRegularClassSymbol(session)?.typeParameterSymbols?.getOrNull(index)
                    ?.resolvedBounds?.forEach { collect(it.coneType, owner, visited) }
            }
        }
    }

    /** The top-level declaration whose contract [declaration] is part of: itself, or its outermost class. */
    private fun ownerOf(declaration: FirCallableDeclaration): String? {
        val id = declaration.symbol.callableId ?: return null
        return id.classId?.let(::ownerOf) ?: id.asSingleFqName().asString()
    }

    private fun ownerOf(classId: ClassId): String = classId.outermostClassId.asSingleFqName().asString()
}
