// NEW: (V4-92) the lookups a public-surface report adds to its source's. The walk reads classes the compiler never
// looked up for the source: the class a star projection projects, the bounds of its type parameters, and every alias on
// the way to the class a type stands for. Incremental compilation recompiles a source only when something recorded as
// its lookup changes, so each class and alias the walk reads is recorded against the source through the compiler's own
// lookup tracker, and a change to any of them rewrites the report.
package splice.firchecks

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.lookupTracker
import org.jetbrains.kotlin.fir.recordClassLikeLookup
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeLookupTag
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.name.ClassId

/** Records that one source's report read the ABI of a class or alias. */
internal fun interface SurfaceLookups {
    operator fun invoke(classId: ClassId)
}

/** The compiler's lookup tracker, the one incremental compilation reads a source's dependencies from. A compile with
 *  no tracker is not incremental: it compiles every source, so there is nothing to record. With a tracker, a source
 *  element names the file the lookup belongs to, as Fir2IrPluginContext.recordLookup passes it; the position is
 *  null there too. recordClassLikeLookup skips a local class and a builtin one, neither of which can change. */
internal object CompilerLookups {
    operator fun invoke(file: FirFile): SurfaceLookups {
        val tracker = file.moduleData.session.lookupTracker ?: return SurfaceLookups { }
        val fileSource = checkNotNull(file.source) {
            "${file.name}: no source element, so the classes its public-surface report reads cannot be recorded"
        }
        return SurfaceLookups { classId -> tracker.recordClassLikeLookup(classId, null, fileSource) }
    }
}

/** The lookups that reading one type takes: the class or alias it names, then each alias on the way to the class it
 *  expands to, which is the chain fullyExpandedType and toRegularClassSymbol follow. Recording the chain itself, and
 *  not hooking the expansion, is deliberate: fullyExpandedType returns a cached expansion without running its hook.
 *  An alias cycle never gets here, because RECURSIVE_TYPEALIAS_EXPANSION is a compile error and a compile with errors
 *  generates no IR. */
internal class TypeLookups(private val session: FirSession, private val lookups: SurfaceLookups) {
    operator fun invoke(type: ConeClassLikeType) {
        var tag: ConeClassLikeLookupTag? = type.lookupTag
        while (tag != null) {
            lookups(tag.classId)
            val alias = tag.toSymbol(session) as? FirTypeAliasSymbol
            tag = (alias?.resolvedExpandedTypeRef?.coneType as? ConeClassLikeType)?.lookupTag
        }
    }
}
