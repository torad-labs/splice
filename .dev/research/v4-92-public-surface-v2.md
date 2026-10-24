# V4-92 public-surface law, compiler-backed: design v3

Designed by an Opus subagent on Oct 8, 2026, against HEAD 26d4db1a5. Step 7 is void: no worktrees.

Revised to v3 by an Opus subagent on Oct 8, 2026, against HEAD d81a6c58d. Every file a v3 code block changes is identical at d81a6c58d and 28503ed6e, where they were read.

**What changed from v2.** Two corrections from a pre-build review. Every other part keeps its v2 meaning.

| Correction | v2 | v3 | Lines |
|---|---|---|---|
| Undeclared edge owner | `Report.parse` accepted an edge owner the report does not declare, and the closure skipped it. A report whose `fix.lib.Store` owner key read `fix.lib.Stone` drew no refusal and graded fix.lib.Hidden an offender. | Every edge owner must be one of that report's declarations. Otherwise the law refuses the report as corrupt, by name. The integrity test holds the Store-to-Stone control. | 88, 95, 107, 1492-1495, 1569-1581, 1634-1640, 1690-1693, 2003, 2036, 2040 |
| Unregistered star-bound reads | The walk read type-parameter bounds behind star projections, and the aliases on the way, without recording them as lookups of the source. An incremental compile could leave a report stale. | `SurfaceLookups` records every class and alias the walk reads against the reporting source, through the compiler's lookup tracker. PublicSurfaceIncrementalTest reproduces the Mid, Box and User chain and an alias route under real incremental compilation, with and without the registration. | 61-69, 146, 159-164, 201-237, 245-252, 321-343, 395, 438-446, 474-478, 539-553, 580-639, 967-1262, 1973-1984, 2036-2039 |

**What v3 has not run.** None of v3's code has been compiled or run. The task forbade Gradle, probe projects and scratch code. Every compiler and Build Tools API call it adds is checked with javap against the 2.3.21 jars, in the table under "Compiler and KGP API calls". What rests on inference is listed under Risks, at the end.

The revised V4-92 spec is below, with incremental compilation left on. One part is unverified: the Gradle wiring has never run, because the task forbids running Gradle. Everything else was compiled and run against kotlin-compiler-embeddable 2.3.21 before the scratchpad sweep. That covers the plugin, the law, the fixture compiler and all 23 acceptance rows. The sweep deleted the raw measurement logs, so the figures below are the ones recorded at run time.

There are no patch files. The code blocks here are the authoritative text, written against HEAD 26d4db1a5. Since v1, quality/architecture/build.gradle.kts has gained the tracked-paths digest and the conventional-candidates read set, so its edits below anchor on content that still exists. I deleted scratchpad/v4-92-ic, scratchpad/spec and my other V4-92 prototype directories. The shared tree was never edited.

## Design and contract

**What changed from v1.** The contract, the law's refusals and rows c01 to c23 keep their meaning.

| Point | v1 | v2 |
|---|---|---|
| Compile mode | `incremental = false` on compileKotlin | Incremental compilation stays on |
| Report unit | One `main.json` per module | One JSON file per compiled main source |
| Plugin options | `publicSurfaceReport` | `publicSurfaceReportDir` and `publicSurfaceSourceRoot`, given together or not at all |
| Format | `splice.public-surface/1` | `splice.public-surface/2`, which adds the SHA-256 of the source bytes |
| Edge targets | Same-module only, filtered by declaration origin | Every non-local class; the law keeps only its own module's |
| Deleted or renamed source | Not applicable, since the whole report was rewritten | compileKotlin removes the report first, and the law refuses any survivor by name |
| Freshness | doFirst deleted the single report | The law hashes every source and compares it with its report |
| Red checks | Steps 2 and 6 edit and revert the tree | In-test mutations inside JUnit's TempDir only |

**Why v1's origin filter is gone.** Under incremental compilation, files that were not recompiled are read back as precompiled classes. The compiler jar has `FirDeclarationOrigin$Precompiled` for exactly that case. v1 kept only targets whose origin was Source, so it dropped every same-module edge into a file outside the dirty set. The plugin now records every class, and the law decides module membership from its own census.

**The guarantee this relies on: Kotlin incremental compilation's recompilation guarantee.**
- A source is recompiled when its own text changes.
- A source is also recompiled when the ABI (application binary interface) of a class or member it depends on changes.
- Recompilation repeats while recompiled classes change their own ABI.
- Anything incremental compilation cannot classify becomes a full rebuild.

These quotes were fetched this session.

From kotlinlang.org/docs/gradle-compilation-and-caches.html, section Incremental compilation:
> "Incremental compilation tracks changes to files in the classpath between builds so that only the files affected by these changes are compiled."
> "When member-level changes are detected, the Kotlin compiler recompiles only the classes that depend on the modified members."
> "When a part of ABI changes, the Kotlin compiler recompiles all classes that depend on the changed class."
> "The first build is never incremental."

From blog.jetbrains.com/kotlin/2022/07/a-new-approach-to-incremental-compilation-in-kotlin/:
> "The Kotlin compiler saves dependencies between the classes being compiled."
> "it's possible to find classes affected by new changes in the ABI and repeat the compilation."

From docs.gradle.org/current/userguide/custom_tasks.html, section Incremental tasks:
> "One or more output files have changed since the previous execution." … "In these cases, Gradle will report all input files as ADDED"
> "In this case, Gradle automatically removes the previous outputs, so the incremental task must only process the given files."

KotlinCompile is such an incremental task. Its action is `AbstractKotlinCompile.execute(org.gradle.work.InputChanges)` in kotlin-gradle-plugin-2.3.21-gradle813.jar. That jar's `getChangedFiles` returns the Build Tools API `SourcesChanges` type, which is the same engine and input the measurement below drove.

**Why per-file reports stay correct.** The plugin keeps a locality property. A source's report depends only on two things: the source's own text, and the ABI of the classes and aliases recorded as lookups of that source. It never depends on which other files share the compile.
- **What the source itself supplies:** its declarations, their effective visibility, and whether a constructor belongs to a sealed class.
- **What comes from the ABI of other classes:** a supertype's class kind, an alias's expansion, a type parameter's bounds behind a star projection, and an overridden member's visibility. Each one is a fact in the serialized ABI of one class or alias.
- **Who records the lookups.** The compiler records the classes a source's own text resolves. The walk reads further. It follows a star projection to the bounds of the projected class, follows any star in those bounds again, and follows every alias on the way to the class it stands for. v2 left those reads unrecorded, so a bound two classes away could change while the reader was never recompiled. In v3, `SurfaceLookups` records each class and alias the walk reads, against the reporting source, through the compiler's own `recordClassLikeLookup`. The overridden member's visibility stays the compiler's own lookup, which v2's Override rows measured.
- **Why a record is enough.** Incremental compilation turns a changed class into the dirty symbol made of its short name and its parent's FQN. `recordClassLikeLookup` records exactly that pair for the source, and the incremental runner stores what was recorded after the compile returns. Both are in the bytecode cited in the API table.

Incremental compilation recompiles a source when its text changes, or when the ABI of anything recorded as its lookup changes. With the registration in place, every class and alias a report reads is recorded as a lookup of that report's source. Given that locality and the guarantee, four cases cover every source:
1. A recompiled source gets its report rewritten from the same inputs a whole compile would see.
2. A source that was not recompiled had no change to its text. It also had no ABI change in any class or alias its report read, because each one is a recorded lookup of that source. Its report therefore already equals what a whole compile would write.
3. A deleted or renamed source compiles nothing, so the guarantee says nothing about it. compileKotlin removes its report before compiling, using the task's own `sources`. The law also refuses any survivor by name.
4. A first build, a compiler-argument change, a plugin-jar change or a touched output runs non-incrementally. Every source is recompiled, and Gradle removes the previous outputs.

The hash in each report catches a source whose text changed but whose report was not rewritten. That is how a compile without the plugin shows up. A plugin can only go missing through a non-incremental run, because the jar is a non-incremental file input and the `-P` arguments are an input property. A non-incremental run removes every report, and the law then refuses the module by name.

One residual rests on the guarantee alone. The hash cannot see an ABI-only change that incremental compilation failed to propagate. The gate of record never relies on incremental compilation, because it runs clean with no build cache, as stated in build-logic/src/main/kotlin/splice.gate-ladder.gradle.kts:102.

**The contract between the plugin and the law.**

```
<module dir>/build/splice/public-surface/main/<source path relative to the module dir>.json
e.g. core/build/splice/public-surface/main/src/main/kotlin/splice/core/Foo.kt.json

{
  "format": "splice.public-surface/2",
  "sha256": "<lowercase hex SHA-256 of the source file's bytes>",
  "declarations": ["<FQN of every public top-level declaration in this source>", ...],
  "edges": {
    "<owner FQN, one of this report's declarations>": ["<outermost FQN of every non-local class a reachable signature of owner names, any module>", ...]
  }
}
```

- **Bytes.** Files are UTF-8, keys are in this order, arrays are sorted, there is one owner per line, and the file ends with a newline. The same source against the same ABI always writes the same bytes.
- **Edges.** An edge to a same-module target means that making the target internal would fail the owner's module with EXPOSED_*. The owner is the outermost class or the top-level callable. Self-edges and local classes are dropped. Aliases are expanded and are never targets.
- **Owners.** Every edge owner is one of the same report's `declarations`. The walk writes an edge only from a declaration whose effective visibility is reachable, and effective visibility is bounded by every container. So the owner, the outermost class or the top-level callable, is a public top-level declaration of this source, and `declareIfTopLevel` has declared it. An owner outside `declarations` is therefore corruption, never a key the closure may skip, and the law refuses the report by name.
- **The law's rule.** Justification by name or star import is unchanged. The closure then follows an owner's edges only into its own module's census, until nothing more is added.
- **Census.** The union of a module's `declarations` must equal the parser's census of public lines, in both directions.

**The law refuses each of these by name, and grades nothing until none remain.**

| Case | Message begins |
|---|---|
| A main source with no report | `<report dir>: no report for [<sources>]` |
| A report whose source is not a main source | `<report>: a report for <path>, which is not a main source of <module>` |
| A report written for other text | `<report>: written for other text than <source> holds now` |
| A report that does not parse, or has another format | `<report>: not a splice.public-surface/2 report (<cause>)` |
| A report with an edge owner it does not declare | `<report>: not a splice.public-surface/2 report (edges name owners [<fqns>], which it does not declare)` |
| The compiler declared fewer names than the parser read | `<report dir>: the compiler did not declare [<fqns>], which the parser read` |
| The compiler declared names the parser did not read | `<report dir>: the parser did not enumerate [<fqns>], which the compiler declared` |

**Measured with plain kotlinc.** The fixture module had eight files:
- Types, declaring One and Two
- Alias, with `typealias Target = One`
- Store, with `read(): Target?`
- Base, an open class with `open fun read(): Any`, plus Covariant
- Impl, with `override fun read(): Covariant`
- Box, with open classes Bound and Other, where Other extends Bound, and `Box<T : Bound>`
- User, with `boxed(): Box<*>?`
- Extra

| Step | Action | Report set |
|---|---|---|
| Whole compile | All eight files together | Eight reports |
| Alone | Store, Impl and User each compiled alone against the whole compile's classes | Byte-identical to the whole compile |
| Alias retarget | Alias changed to `= Two` and compiled alone | Store.kt.json still names One, since plain kotlinc compiles only what it is given |
| Then Store | Store compiled alone next | Store names Two, and the set equals a fresh whole compile |
| Override | Base.read made internal, then Base alone, then Impl alone | Impl's edges become Base only, and the set equals a whole compile |
| Bound | Box's bound changed to Other, then Box alone, then User alone | boxed's edges become Box and Other, and the set equals a whole compile |
| Delete | Extra.kt deleted, nothing compiled | Extra.kt.json survives until the cleanup removes it; then the set equals a whole compile |
| v1 plugin | Store, Impl and User each alone, with v1's origin filter | Empty edges, where a whole compile wrote One for Store, Base and Covariant for Impl, and Bound and Box for boxed |

The lone compiles have one pitfall. Pointing `-d` at the shared output rewrites META-INF/lib.kotlin_module with only the lone file's facades. After that, `Target` resolved to kotlin.annotation.Target. A faithful emulation compiles into a temp directory, merges the classes back, keeps the module mapping, and passes `-Xfriend-paths` for the shared output.

**Measured with the incremental compilation engine KGP (the Kotlin Gradle plugin) drives.** This used the Build Tools API with `CompilationService.loadImplementation`, `useIncrementalCompilation`, and `SourcesChanges.Known` or `Unknown`, with classpath snapshots.

| Step | Change | Files the engine compiled | Reports |
|---|---|---|---|
| First build | Changes unknown | All files, logging `UNKNOWN_CHANGES_IN_GRADLE_INPUTS` | Eight written |
| Body | Store's body edited | Store.kt only | Only Store.kt.json rewritten; equals a whole compile |
| Alias | Alias retargeted to Two | Alias.kt, then Store.kt | Both rewritten; equals a whole compile |
| Override | Base.read made internal | Base.kt, then Impl.kt | Impl's edges become Base only; equals a whole compile |
| Bound | Box's bound changed to Other | Box.kt, then User.kt | boxed's edges become Box and Other; equals a whole compile |
| Delete | Extra.kt removed | Nothing | Extra.kt.json survives until the cleanup; then equals a whole compile |
| v1 filter | The body step, with v1's origin filter | Store.kt only | Store.kt.json has empty edges; every incremental step diverged |

**What the Bound rows did not cover.** Both Bound rows changed the bound of Box, the class User's own text names. Box is a compiler lookup of User, so the change reached User. A bound one class further away was never measured: `Box<T : Mid<*>>` with Mid's bound changed. User's walk reads that bound, but in v2 nothing recorded Mid as a lookup of User. That is v3's second correction, and PublicSurfaceIncrementalTest measures it under incremental compilation.

**Other results recorded before the sweep.**

| Check | Result |
|---|---|
| Plugin tests | 8 of 8 pass |
| Plugin tests against v1's origin filter | 3 fail: the golden, the alone compile and the report-change test |
| detekt, ast-grep | Clean; both failed on earlier drafts, so each check can fail |
| Law and contract tests, live test skipped | 12 of 12 pass, with 0 row disagreements |
| Live snapshot taken at 18:39Z | 1104 main sources and 1104 reports; all 11 law tests pass |
| Live verdict | 1125 examined, 22 offenders equal to the 22 baseline entries, 0 problems |

**Compiler and KGP API calls, verified with javap.** The compiler calls are all in the first jar, and the Gradle calls in the second one. v3's Build Tools API calls are in the third:

```
~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.3.21/c4b47f8e33cdbeedd8c1415ab9f9ade870388162/kotlin-compiler-embeddable-2.3.21.jar
~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-gradle-plugin/2.3.21/24cd0fef7f2b18be74a59975d6f941798a0e4b96/kotlin-gradle-plugin-2.3.21-gradle813.jar
~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-build-tools-api/2.3.21/9b0a49a4d4c570c63a3a2553816bf0539536ab69/kotlin-build-tools-api-2.3.21.jar
```

| Call in the code | Verified signature | Class |
|---|---|---|
| `pluginId`, `pluginOptions`, `processOption` | `getPluginId()`, `getPluginOptions(): Collection<AbstractCliOption>`, `processOption(AbstractCliOption, String, CompilerConfiguration)` | `org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor` |
| `CliOption(...)` | `CliOption(String, String, String, boolean, boolean)` | `org.jetbrains.kotlin.compiler.plugin.CliOption` |
| `CompilerConfigurationKey.create` | `static <T> create(String)` | `org.jetbrains.kotlin.config.CompilerConfigurationKey` |
| `configuration.get`, `put` | `get(CompilerConfigurationKey<T>)`, `put(CompilerConfigurationKey<T>, T)` | `org.jetbrains.kotlin.config.CompilerConfiguration` |
| `IrGenerationExtension.registerExtension(it)` | `registerExtension(ProjectExtensionDescriptor<T>, T)`; the Companion extends `ProjectExtensionDescriptor<IrGenerationExtension>` | `CompilerPluginRegistrar$ExtensionStorage` |
| `generate` | `generate(IrModuleFragment, IrPluginContext)` | `org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension` |
| `moduleFragment.files` | `getFiles(): List<IrFile>` | `org.jetbrains.kotlin.ir.declarations.IrModuleFragment` |
| `irFile.metadata`, `fileEntry.name` | `getMetadata(): MetadataSource`, `getFileEntry()`, `IrFileEntry.getName()` | `IrMetadataSourceOwner`, `IrFile`, `org.jetbrains.kotlin.ir.IrFileEntry` |
| `FirMetadataSource.File.fir` | `getFir(): FirFile` | `org.jetbrains.kotlin.fir.backend.FirMetadataSource$File` |
| `fir.moduleData.session` | `getModuleData()`, `FirModuleData.getSession()` | `FirDeclaration`, `org.jetbrains.kotlin.fir.FirModuleData` |
| `file.declarations` under `@OptIn(DirectDeclarationsAccess::class)` | `getDeclarations(): List<FirDeclaration>`; `DirectDeclarationsAccess` is an annotation interface | `FirFile`, `org.jetbrains.kotlin.fir.declarations` |
| `superTypeRefs`, `classKind` | `getSuperTypeRefs(): List<FirTypeRef>`, `getClassKind()` | `FirRegularClass` |
| `valueParameters`, `returnTypeRef`, `receiverParameter`, `contextParameters` | getters as named | `FirFunction`, `FirCallableDeclaration` |
| `typeParameters`, `resolvedBounds` | `getTypeParameters()`, `getResolvedBounds(): List<FirResolvedTypeRef>` | `FirTypeParameterRefsOwner`, `FirTypeParameterSymbol` |
| `coneType` | `getConeType(FirTypeRef): ConeKotlinType` | `org.jetbrains.kotlin.fir.types.FirTypeUtilsKt` |
| `effectiveVisibility`, `isFromSealedClass` | `getEffectiveVisibility(FirMemberDeclaration)`, `isFromSealedClass(FirMemberDeclaration)` | `org.jetbrains.kotlin.fir.declarations.utils.FirStatusUtilsKt` |
| `expandedConeType` | `getExpandedConeType(FirTypeAlias): ConeClassLikeType` | `org.jetbrains.kotlin.fir.declarations.utils.FirDeclarationUtilKt` |
| `isLocal` on a class symbol | `isLocal(FirClassLikeSymbol<?>)` | `org.jetbrains.kotlin.fir.declarations.utils.FirSymbolStatusUtilsKt` |
| `fullyExpandedType(session)` | `fullyExpandedType(ConeClassLikeType, FirSession, Function1)` with a `$default` bridge | `org.jetbrains.kotlin.fir.resolve.TypeExpansionUtilsKt` |
| `lookupTag.toSymbol(session)` | `ConeClassLikeType.getLookupTag()`, `toSymbol(ConeClassLikeLookupTag, FirSession): FirClassLikeSymbol<?>` | `org.jetbrains.kotlin.fir.resolve.ToSymbolUtilsKt` |
| `toRegularClassSymbol(session)` | `toRegularClassSymbol(ConeKotlinType or ConeClassLikeType, FirSession)` | `org.jetbrains.kotlin.fir.resolve.ToSymbolUtilsKt` |
| `typeParameterSymbols`, `classId` | `getTypeParameterSymbols()`, `getClassId()` | `FirClassLikeSymbol` |
| `session.typeContext` | `getTypeContext(FirSession): ConeInferenceContext` | `org.jetbrains.kotlin.fir.types.TypeComponentsKt` |
| `EffectiveVisibility.Internal.relation(v, typeContext)` | `relation(EffectiveVisibility, TypeCheckerProviderContext): Permissiveness`, with LESS, SAME, MORE and UNKNOWN; subclasses Internal, Public, Local and PrivateInClass | `org.jetbrains.kotlin.descriptors.EffectiveVisibility` |
| `EnumSuperTypeRef`, `EnumGeneratedDeclaration` | Both are final subclasses of `KtFakeSourceElementKind` | `org.jetbrains.kotlin.KtFakeSourceElementKind` |
| `FirLocalPropertySymbol` | Extends `FirPropertySymbol` | `org.jetbrains.kotlin.fir.symbols.impl` |
| `outermostClassId`, `isNestedClass`, `asSingleFqName`, `callableId.classId` | getters as named | `org.jetbrains.kotlin.name.ClassId`, `CallableId` |
| The rules the walk copies | `checkClass`, `checkSupertypes`, `checkParameterBounds`, `checkTypeAlias`, `checkFunction`, `checkProperty` | `org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirExposedVisibilityDeclarationChecker` |
| Why the origin filter broke | `FirDeclarationOrigin$Precompiled` exists | `org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin` |
| `sources` in the doFirst | `getSources(): FileCollection` | `org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompileTool` |
| KotlinCompile is an incremental task | `execute(InputChanges)`, `getChangedFiles(...)` returning `SourcesChanges` | `org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompile` |

**v3's lookup registration, verified with javap in the compiler jar.**

| Call in the code | Verified signature or bytecode | Class |
|---|---|---|
| `file.moduleData.session.lookupTracker` | `static FirLookupTrackerComponent getLookupTracker(FirSession)`, null when no tracker is registered | `org.jetbrains.kotlin.fir.FirLookupTrackerComponentKt` |
| `tracker.recordClassLikeLookup(classId, null, fileSource)` | `static void recordClassLikeLookup(FirLookupTrackerComponent, ClassId, KtSourceElement, KtSourceElement)`. Offsets 13-31 skip a local ClassId and any of `StandardClassIds.getAllBuiltinTypes()`. Offsets 35-65 call `recordLookup(String, String, KtSourceElement, KtSourceElement)` with `asSingleFqName().shortName()` and `parent()` | `org.jetbrains.kotlin.fir.FirLookupTrackerComponentKt` |
| The precedent: a null source and the file's source | `recordLookup(FqName, IrFile)` calls `getLookupTracker` at offset 35, `getFileSource(IrFile)` at 50, and `recordFqNameLookup(FirLookupTrackerComponent, FqName, KtSourceElement, KtSourceElement)` at 53. `getFileSource` is `IrFile.getMetadata()`, `instanceof FirMetadataSource$File`, `getFir()`, `FirFile.getSource()` | `org.jetbrains.kotlin.fir.backend.Fir2IrPluginContext` |
| Where a record goes | `recordLookup(String, String, KtSourceElement, KtSourceElement)`, which reaches `LookupTracker.record(String, Position, String, ScopeKind, String)` | `org.jetbrains.kotlin.fir.IncrementalPassThroughLookupTrackerComponent` |
| When the runner stores the records | `new LookupTrackerImpl` at offset 251 of `doCompile`, `runCompiler` at 613, `BuildUtilKt.update(LookupStorage, LookupTracker, Iterable, Iterable)` at 1376, `mapLookupSymbolsToFiles` at 1694 | `org.jetbrains.kotlin.incremental.IncrementalCompilerRunner` |
| What a changed class dirties | `getChangedAndImpactedSymbols(List<ChangeInfo>, Iterable, ICReporter)` tests `ChangeInfo$SignatureChanged` at offset 90, then builds `LookupSymbol(String, String)` at 277 from `FqName.shortName()` at 253 and `FqName.parent()` at 243 | `org.jetbrains.kotlin.incremental.BuildUtilKt` |
| `file.source`, `file.name` | `getSource(): KtSourceElement`, nullable; `getName()` | `org.jetbrains.kotlin.fir.declarations.FirFile` |
| `tag.toSymbol(session) as? FirTypeAliasSymbol` | `toSymbol(ConeClassLikeLookupTag, FirSession): FirClassLikeSymbol<?>`; `FirTypeAliasSymbol extends FirClassLikeSymbol<FirTypeAlias>` | `ToSymbolUtilsKt`, `org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol` |
| `alias.resolvedExpandedTypeRef.coneType` | `getResolvedExpandedTypeRef(): FirResolvedTypeRef`, which calls `lazyResolveToPhase` first; `FirResolvedTypeRef.getConeType()` is a member | `FirTypeAliasSymbol`, `org.jetbrains.kotlin.fir.types.FirResolvedTypeRef` |
| `type.lookupTag`, `tag.classId` | `ConeClassLikeType.getLookupTag(): ConeClassLikeLookupTag`, `ConeClassLikeLookupTag.getClassId()` | `org.jetbrains.kotlin.fir.types` |
| An alias cycle never reaches IR | `getRECURSIVE_TYPEALIAS_EXPANSION(): KtDiagnosticFactory0` | `org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors` |
| Why a hook inside type expansion would not work | `fullyExpandedType` returns `ConeClassLikeTypeImpl`'s cached expansion without calling its expansion hook again | `org.jetbrains.kotlin.fir.resolve.TypeExpansionUtilsKt` |
| How the test's unregistered plugin loads | `PluginCliParser.createClassLoader(Iterable, Disposable)` builds a `URLClassLoader` whose parent comes from `Class.getClassLoader()` at offset 138. `ServiceLoaderLite.loadImplementations(Class, URLClassLoader)` reads services only from those URLs, and a directory through `findImplementationsInDirectory` | `org.jetbrains.kotlin.cli.jvm.plugins.PluginCliParser`, `org.jetbrains.kotlin.util.ServiceLoaderLite` |

**v3's incremental test, verified with javap in the Build Tools API jar.** None of the members used is deprecated. The deprecated ones it avoids are `createJvmCompilationOperation`, `createClasspathSnapshottingOperation`, `createSnapshotBasedIcOptions` and `set` on a built `JvmCompilationOperation`.

| Call in the code | Verified signature | Class |
|---|---|---|
| `KotlinToolchains.loadImplementation(loader)` | `static KotlinToolchains loadImplementation(ClassLoader)` | `org.jetbrains.kotlin.buildtools.api.KotlinToolchains` |
| `getToolchain`, `createInProcessExecutionPolicy`, `createBuildSession` | `<T> T getToolchain(Class<T>)`, `ExecutionPolicy$InProcess createInProcessExecutionPolicy()`, `KotlinToolchains$BuildSession createBuildSession()` | `KotlinToolchains` |
| `session.executeOperation(op, policy, logger)`, `use` | `<R> R executeOperation(BuildOperation<R>, ExecutionPolicy, KotlinLogger)`; the session extends `AutoCloseable` | `KotlinToolchains$BuildSession` |
| `jvmCompilationOperationBuilder`, `classpathSnapshottingOperationBuilder` | `JvmCompilationOperation$Builder jvmCompilationOperationBuilder(List<Path>, Path)`, `JvmClasspathSnapshottingOperation$Builder classpathSnapshottingOperationBuilder(Path)` | `org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain` |
| `compilerArguments`, `set`, `build`, `snapshotBasedIcConfigurationBuilder` | `getCompilerArguments(): JvmCompilerArguments$Builder`, `<V> void set(JvmCompilationOperation$Option<V>, V)`, `build()`, `snapshotBasedIcConfigurationBuilder(Path, SourcesChanges, List<Path>, Path)` | `jvm.operations.JvmCompilationOperation$Builder` |
| `INCREMENTAL_COMPILATION` | `Option<JvmIncrementalCompilationConfiguration>`; `JvmSnapshotBasedIncrementalCompilationConfiguration implements JvmIncrementalCompilationConfiguration`, and its builder has `build()` | `JvmCompilationOperation`, `org.jetbrains.kotlin.buildtools.api.jvm` |
| `applyArgumentStrings`, `set(COMPILER_PLUGINS, ...)` | `CommonToolArguments$Builder.applyArgumentStrings(List<String>)`; `CommonCompilerArguments$Builder.set(CommonCompilerArgument<V>, V)`; `COMPILER_PLUGINS: CommonCompilerArgument<List<CompilerPlugin>>` | `org.jetbrains.kotlin.buildtools.api.arguments` |
| `CompilerPlugin(...)`, `CompilerPluginOption(...)` | `CompilerPlugin(String, List<Path>, List<CompilerPluginOption>, Set<CompilerPluginPartialOrder>)`, `CompilerPluginOption(String, String)` | `org.jetbrains.kotlin.buildtools.api.arguments` |
| `SourcesChanges.Known(...)`, `SourcesChanges.Unknown` | `SourcesChanges$Known(List<File>, List<File>)`; `SourcesChanges$Unknown.INSTANCE` | `org.jetbrains.kotlin.buildtools.api` |
| `saveSnapshot(path)` | `default void saveSnapshot(Path)` | `jvm.ClasspathEntrySnapshot` |
| `CompilationResult.COMPILATION_SUCCESS` | an enum constant beside `COMPILATION_ERROR`, `COMPILATION_OOM_ERROR` and `COMPILER_INTERNAL_ERROR` | `org.jetbrains.kotlin.buildtools.api.CompilationResult` |
| `CollectingLogger : KotlinLogger` | `isDebugEnabled()`, `error(String, Throwable)` and `warn(String, Throwable)` with a nullable Throwable, `info(String)`, `debug(String)`, `lifecycle(String)` | `org.jetbrains.kotlin.buildtools.api.KotlinLogger` |
| `@file:OptIn(ExperimentalBuildToolsApi::class)` | an annotation interface, a `RequiresOptIn` marker | `org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi` |

The impl jar, kotlin-build-tools-impl-2.3.21, declares the api, kotlin-build-tools-cri-impl, kotlin-compiler-embeddable and kotlin-compiler-runner, all 2.3.21, as its dependencies in its POM. kotlin-compiler-runner adds kotlin-daemon-client 2.3.21 and kotlinx-coroutines-core-jvm 1.8.0. gradle/verification-metadata.xml already pins every one of them.

## Code

| File | Change |
|---|---|
| quality/compiler-plugin/src/main/kotlin/splice/firchecks/PublicSurfaceReport.kt | new |
| quality/compiler-plugin/src/main/kotlin/splice/firchecks/PublicSurfaceWalk.kt | new |
| quality/compiler-plugin/src/main/kotlin/splice/firchecks/SurfaceLookups.kt | new in v3 |
| quality/compiler-plugin/src/main/kotlin/splice/firchecks/MustConsumeCompilerPluginRegistrar.kt | edit, full text below |
| quality/compiler-plugin/src/main/resources/META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor | new |
| quality/compiler-plugin/src/test/kotlin/splice/firchecks/PublicSurfaceReportTest.kt | new |
| quality/compiler-plugin/src/test/kotlin/splice/firchecks/PublicSurfaceIncrementalTest.kt | new in v3 |
| quality/compiler-plugin/src/test/kotlin/splice/firchecks/UnregisteredLookupsRegistrar.kt | new in v3 |
| quality/compiler-plugin/build.gradle.kts | edit in v3 |
| gradle/libs.versions.toml | edit in v3 |
| build.gradle.kts | edit |
| quality/architecture/build.gradle.kts | edit |
| quality/architecture/src/test/kotlin/splice/quality/FixtureCompiler.kt | new |
| quality/architecture/src/test/kotlin/splice/quality/PublicSurfaceLawTest.kt | edit |
| quality/architecture/src/test/kotlin/splice/quality/PublicSurfaceContractTest.kt | new, same as v1 |

**PublicSurfaceReport.kt**

```kotlin
// NEW: (V4-92) the public-surface reports: their two -P options, the per-source ledger, and the JSON file per compiled
// source that the architecture law reads.
package splice.firchecks

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import java.io.File
import java.security.MessageDigest
import java.util.SortedSet

internal const val PLUGIN_ID: String = "splice.fir-checks"
internal const val REPORT_FORMAT: String = "splice.public-surface/2"
private const val HEX = 16
private const val UNICODE_ESCAPE_WIDTH = 4

internal val reportDirKey: CompilerConfigurationKey<String> =
    CompilerConfigurationKey.create("splice public-surface report directory")
internal val sourceRootKey: CompilerConfigurationKey<String> =
    CompilerConfigurationKey.create("splice public-surface source root")

internal val reportDirOption: CliOption = CliOption(
    optionName = "publicSurfaceReportDir",
    valueDescription = "<dir>",
    description = "Write one public-surface report (JSON) per compiled source under <dir>",
    required = false,
    allowMultipleOccurrences = false,
)

internal val sourceRootOption: CliOption = CliOption(
    optionName = "publicSurfaceSourceRoot",
    valueDescription = "<dir>",
    description = "Key each report by its source's path relative to <dir>, the module's directory",
    required = false,
    allowMultipleOccurrences = false,
)

@OptIn(ExperimentalCompilerApi::class)
internal class FirChecksCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = PLUGIN_ID
    override val pluginOptions: Collection<AbstractCliOption> = listOf(reportDirOption, sourceRootOption)

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        val key = when (option.optionName) {
            reportDirOption.optionName -> reportDirKey
            sourceRootOption.optionName -> sourceRootKey
            else -> error("unknown $PLUGIN_ID option ${option.optionName}")
        }
        configuration.put(key, value)
    }
}

/** Runs once per successful compilation, after FIR is resolved and checked, over exactly the sources it compiled: all
 *  of them on a full build, the dirty ones on an incremental one. Each source gets its own report at
 *  `<reportDir>/<its path relative to sourceRoot>.json`, and that report depends on nothing but the source's text and
 *  what its signatures resolve to, so a source compiled alone writes the bytes a whole-module compile writes. Every
 *  class the walk reads is recorded as a lookup of that source through [lookupsOf], so incremental compilation
 *  recompiles the source, and rewrites its report, when one of them changes. */
internal class PublicSurfaceReportExtension(
    private val reportDir: File,
    sourceRoot: File,
    private val lookupsOf: SurfaceLookupsOf = CompilerLookups,
) : IrGenerationExtension {
    private val root = sourceRoot.absoluteFile.normalize()

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        for (irFile in moduleFragment.files) {
            val fir = checkNotNull((irFile.metadata as? FirMetadataSource.File)?.fir) {
                "${irFile.fileEntry.name}: no FIR behind this IR file, so its public-surface report cannot be written"
            }
            val source = File(irFile.fileEntry.name).absoluteFile.normalize()
            val key = source.relativeTo(root).invariantSeparatorsPath
            check(key != ".." && !key.startsWith("../")) { "$source is outside $root, so no report path can name it" }
            val ledger = SurfaceLedger()
            PublicSurfaceWalk(fir.moduleData.session, ledger, lookupsOf(fir)).walk(fir)
            val out = File(reportDir, "$key.json").absoluteFile
            out.parentFile.mkdirs()
            out.writeText(ledger.json(sha256(source.readBytes())), Charsets.UTF_8)
        }
    }

    /** The SHA-256 of a source's bytes, the way the law hashes the same file: a report is about one exact text. */
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
}

/** One source's surface: its public top-level declarations, and each one's reach. */
internal class SurfaceLedger {
    private val declarations: SortedSet<String> = sortedSetOf()
    private val edges = sortedMapOf<String, SortedSet<String>>()

    fun declare(fqn: String) {
        declarations += fqn
    }

    fun reach(owner: String, target: String) {
        if (owner != target) edges.getOrPut(owner) { sortedSetOf() } += target
    }

    /** Sorted, one owner per line: the same source and the same classpath always write the same bytes. */
    fun json(sha256: String): String = buildString {
        append("{\n  \"format\": ").append(quote(REPORT_FORMAT)).append(",\n")
        append("  \"sha256\": ").append(quote(sha256)).append(",\n")
        append("  \"declarations\": [").append(declarations.joinToString(", ", transform = ::quote)).append("],\n")
        append("  \"edges\": {")
        edges.entries.forEachIndexed { index, (owner, targets) ->
            append(if (index == 0) "\n    " else ",\n    ").append(quote(owner)).append(": [")
            append(targets.joinToString(", ", transform = ::quote)).append("]")
        }
        append(if (edges.isEmpty()) "}\n}\n" else "\n  }\n}\n")
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when {
                ch == '"' || ch == '\\' -> append('\\').append(ch)
                ch < ' ' -> append("\\u").append(ch.code.toString(HEX).padStart(UNICODE_ESCAPE_WIDTH, '0'))
                else -> append(ch)
            }
        }
        append('"')
    }
}
```

**PublicSurfaceWalk.kt.** Compared with v1, the header changed, the `FirDeclarationOrigin` import is gone, and `collect` no longer filters by origin. v3 adds the `lookups` parameter and records every class the walk reads through it: each written supertype in `regularClass`, and each class-like type in `collect`. A star-projected class is the type `collect` was given, so it is recorded before `collectArguments` reads its bounds, and each bound is recorded when `collect` reaches it.

```kotlin
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
```

**SurfaceLookups.kt, new in v3.** The registration goes through the compiler's own `recordClassLikeLookup`, the call FIR's type resolution makes for the classes a source names, with the file's source element as `Fir2IrPluginContext.recordLookup` passes it. Forcing the consumer's recompilation by other means is not the fix: the lookup is what incremental compilation reads. `SurfaceLookups` is the one seam, so PublicSurfaceIncrementalTest can remove only the recording and keep the alias walk. There are three call sites. `PublicSurfaceReportExtension.generate` gets one `SurfaceLookups` per compiled FirFile from `CompilerLookups`. `PublicSurfaceWalk.regularClass` registers each written supertype, and `PublicSurfaceWalk.collect` registers each class-like type. Both go through `TypeLookups`, which calls `recordClassLikeLookup` once per class or alias on the type's chain.

```kotlin
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

/** The [SurfaceLookups] of one compiled source. */
internal fun interface SurfaceLookupsOf {
    operator fun invoke(file: FirFile): SurfaceLookups
}

/** The compiler's lookup tracker, the one incremental compilation reads a source's dependencies from. A compile with
 *  no tracker is not incremental: it compiles every source, so there is nothing to record. With a tracker, a source
 *  element names the file the lookup belongs to, as Fir2IrPluginContext.recordLookup passes it; the position is
 *  null there too. recordClassLikeLookup skips a local class and a builtin one, neither of which can change. */
internal object CompilerLookups : SurfaceLookupsOf {
    override fun invoke(file: FirFile): SurfaceLookups {
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
```

**MustConsumeCompilerPluginRegistrar.kt, full text.** The service file holds the single line `splice.firchecks.FirChecksCommandLineProcessor`.

```kotlin
// NEW: (discipline L4) the K2 compiler-plugin entry point. Discovered via the
// META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar resource. Its two -P options,
// publicSurfaceReportDir and publicSurfaceSourceRoot (FirChecksCommandLineProcessor), add the V4-92 public-surface
// reports, one per compiled source, to that compilation.
package splice.firchecks

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar.ExtensionStorage
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import java.io.File

@OptIn(ExperimentalCompilerApi::class)
internal class MustConsumeCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(MustConsumeFirExtensionRegistrar())
        surfaceReports(configuration)?.let { IrGenerationExtension.registerExtension(it) }
    }

    /** The V4-92 report extension the two options ask for, or null when neither is given. One without the other is
     *  an error: a report directory with no root has no key to write under, and a root alone is an option no one reads. */
    private fun surfaceReports(configuration: CompilerConfiguration): PublicSurfaceReportExtension? {
        val reportDir = configuration.get(reportDirKey)
        val sourceRoot = configuration.get(sourceRootKey)
        if (reportDir == null && sourceRoot == null) return null
        require(reportDir != null && sourceRoot != null) {
            "$PLUGIN_ID: ${reportDirOption.optionName} and ${sourceRootOption.optionName} are given together or " +
                "not at all"
        }
        return PublicSurfaceReportExtension(File(reportDir), File(sourceRoot))
    }
}
```

**PublicSurfaceReportTest.kt.** The module's test task already sets `splice.firChecksPluginJar`, as quality/compiler-plugin/build.gradle.kts:69 shows.

```kotlin
// NEW (V4-92): the public-surface reports' golden. Drives K2JVMCompiler in-process with -Xplugin=<this module's jar>
// and its two -P options over a four-file module whose declarations hold every reachability rule the walk copies from
// FirExposedVisibilityDeclarationChecker, and compares each source's report byte for byte. It also pins what
// incremental compilation leans on: a source compiled alone writes what a whole-module compile writes, and a report
// changes only when its own source is compiled.
package splice.firchecks

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class PublicSurfaceReportTest(@param:TempDir private val workDir: Path) {
    private val module = workDir.resolve("lib")
    private val reports = module.resolve("build/reports")
    private val out = workDir.resolve("out")

    @Test
    fun `each compiled source gets the compiler's reachability, byte for byte`() {
        val result = compile(GOLDEN_SOURCES)
        assertEquals(ExitCode.OK, result.exitCode, result.output)
        assertEquals(golden(GOLDEN_SOURCES), written())
    }

    @Test
    fun `the golden can fail - an open class's protected constructor reaches what a sealed one's does not`() {
        val sealed = GOLDEN_SOURCES.getValue(SEALED)
        val opened = GOLDEN_SOURCES + (SEALED to sealed.replace("public sealed class", "public open class"))
        val result = compile(opened)
        assertEquals(ExitCode.OK, result.exitCode, result.output)
        val report = written().getValue("$SEALED.json")
        assertNotEquals(golden(opened).getValue("$SEALED.json"), report, "the golden tells the two apart")
        assertTrue(report.contains("\"fix.lib.Sealed\": [\"fix.lib.CtorOnly\", \"fix.lib.MemberOnly\""), report)
    }

    @Test
    fun `a source compiled alone writes what the whole module wrote`() {
        assertEquals(ExitCode.OK, compile(ALONE_SOURCES).exitCode)
        val whole = written()
        val alone = compile(mapOf(STORE to ALONE_SOURCES.getValue(STORE)), listOf(out), workDir.resolve("alone"))
        assertEquals(ExitCode.OK, alone.exitCode, alone.output)
        assertEquals(whole, written(), "Store.kt alone reads One from the classes the whole compile left")
    }

    @Test
    fun `a report changes only when its own source is compiled - what incremental compilation must recompile`() {
        assertEquals(ExitCode.OK, compile(ALONE_SOURCES).exitCode)
        val before = written().getValue("$STORE.json")
        val moved = ALONE_SOURCES.getValue(ALIAS).replace("= One", "= Two")
        val alias = workDir.resolve("alias")
        val aliasAlone = compile(mapOf(ALIAS to moved), listOf(out), alias)
        assertEquals(ExitCode.OK, aliasAlone.exitCode, aliasAlone.output)
        assertEquals(before, written().getValue("$STORE.json"), "Store.kt was not compiled, so its report is as it was")
        val storeSource = mapOf(STORE to ALONE_SOURCES.getValue(STORE))
        val store = compile(storeSource, listOf(alias, out), workDir.resolve("store"))
        assertEquals(ExitCode.OK, store.exitCode, store.output)
        val recompiled = written()
        val storeReport = recompiled.getValue("$STORE.json")
        assertTrue(storeReport.contains("\"fix.lib.Store\": [\"fix.lib.Two\""), storeReport)
        reports.toFile().deleteRecursively()
        assertEquals(ExitCode.OK, compile(ALONE_SOURCES + (ALIAS to moved), into = workDir.resolve("again")).exitCode)
        assertEquals(written(), recompiled, "once both are compiled, the set is a whole compile's of the new text")
    }

    @Test
    fun `no options, no reports`() {
        val result = compile(GOLDEN_SOURCES, options = emptyList())
        assertEquals(ExitCode.OK, result.exitCode, result.output)
        assertEquals(emptyMap<String, String>(), written(), "the reports are opt-in per compilation")
    }

    @Test
    fun `a misspelled option is a compile error`() {
        val result = compile(GOLDEN_SOURCES, options = listOf("-P", "plugin:$PLUGIN_ID:publicSurfaceReportDr=$reports"))
        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode, result.output)
        assertTrue(result.output.contains("unsupported plugin option"), result.output)
    }

    @Test
    fun `one option without the other, or a source outside the root, fails the compile`() {
        val half = compile(GOLDEN_SOURCES, options = listOf("-P", "plugin:$PLUGIN_ID:publicSurfaceReportDir=$reports"))
        assertEquals(ExitCode.INTERNAL_ERROR, half.exitCode, half.output)
        assertTrue(half.output.contains("are given together or not at all"), half.output)
        val outside = compile(GOLDEN_SOURCES, options = options(root = workDir.resolve("elsewhere")))
        assertEquals(ExitCode.INTERNAL_ERROR, outside.exitCode, outside.output)
        assertTrue(outside.output.contains("so no report path can name it"), outside.output)
    }

    @Test
    fun `without the plugin the compiler ignores the options in silence - why the law hashes every source`() {
        val result = compile(GOLDEN_SOURCES, plugin = false)
        assertEquals(ExitCode.OK, result.exitCode, result.output)
        assertEquals(emptyMap<String, String>(), written(), "an unregistered plugin id writes nothing and says nothing")
    }

    private data class CompileResult(val exitCode: ExitCode, val output: String)

    private fun options(dir: Path = reports, root: Path = module): List<String> = listOf(
        "-P",
        "plugin:$PLUGIN_ID:publicSurfaceReportDir=$dir",
        "-P",
        "plugin:$PLUGIN_ID:publicSurfaceSourceRoot=$root",
    )

    /** Writes [sources] under the module and compiles exactly those, against [classpath] and the test's own. */
    private fun compile(
        sources: Map<String, String>,
        classpath: List<Path> = emptyList(),
        into: Path = out,
        plugin: Boolean = true,
        options: List<String> = options(),
    ): CompileResult {
        val pluginJar = requireNotNull(System.getProperty("splice.firChecksPluginJar")) {
            "system property splice.firChecksPluginJar (set by the test task) is missing"
        }
        val files = sources.map { (path, text) ->
            module.resolve(path).also { Files.createDirectories(it.parent) }.also { Files.writeString(it, "$text\n") }
        }
        val loaded = if (plugin) listOf("-Xplugin=$pluginJar") else emptyList()
        val args = loaded + options + listOf(
            "-Xexplicit-api=strict", "-Werror", "-no-stdlib", "-no-reflect", "-module-name", "lib",
            "-cp", (classpath.map(Path::toString) + System.getProperty("java.class.path")).joinToString(File.pathSeparator),
            "-d", Files.createDirectories(into).toString(),
        ) + files.map { it.toString() }
        val buffer = ByteArrayOutputStream()
        val exit = PrintStream(buffer, true, Charsets.UTF_8).use { stream ->
            K2JVMCompiler().exec(stream, *args.toTypedArray())
        }
        return CompileResult(exit, buffer.toString(Charsets.UTF_8))
    }

    /** Every report under the report directory, by its path there. */
    private fun written(): Map<String, String> = reports.toFile().walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(reports.toFile()).invariantSeparatorsPath to it.readText() }

    /** [GOLDEN_REPORTS] for [sources]: each report carries the SHA-256 of the exact text its source was written with. */
    private fun golden(sources: Map<String, String>): Map<String, String> =
        GOLDEN_REPORTS.entries.associate { (path, report) ->
            val hash = sha256("${sources.getValue(path)}\n")
            "$path.json" to "${report.replace(SHA_SLOT, hash)}\n"
        }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val SEALED = "src/main/kotlin/Sealed.kt"
        const val ALIAS = "src/main/kotlin/Alias.kt"
        const val STORE = "src/main/kotlin/Store.kt"
        const val SHA_SLOT = "<sha256>"

        /** Each declaration holds one rule: a sealed constructor reaches nothing (CtorOnly), a sealed class's
         *  protected member does (MemberOnly), a class's class supertype does and its interfaces do not, an
         *  implicit override's covariant return does, an enum's implicit private constructor does not, an alias
         *  annotation's text does not, and an internal class's public member does not. */
        val GOLDEN_SOURCES: Map<String, String> = mapOf(
            SEALED to """
                package fix.lib

                public sealed class Sealed protected constructor(hidden: CtorOnly?) {
                    protected fun read(): MemberOnly = MemberOnly()
                }

                public open class Bridge : Sealed(null)

                public class CtorOnly

                public class MemberOnly
            """.trimIndent(),
            "src/main/kotlin/Override.kt" to """
                package fix.lib

                public open class Base

                public interface Port {
                    public fun read(): Base
                }

                public class Store : Port {
                    override fun read(): Covariant = Covariant()
                }

                public class Covariant : Base()
            """.trimIndent(),
            "src/main/kotlin/Enum.kt" to """
                package fix.lib

                public enum class Mode(private val hidden: EnumOnly) { FIRST(EnumOnly()) }

                public class EnumOnly
            """.trimIndent(),
            ALIAS to """
                package fix.lib

                @Target(AnnotationTarget.TYPEALIAS, AnnotationTarget.CLASS)
                public annotation class Ann(public val message: String)

                @Ann("ignored = Text")
                public typealias Alias = Int

                public class Text

                internal class Internal {
                    public fun leak(): Text = Text()
                }
            """.trimIndent(),
        )

        /** A report names every class a reachable signature names, the standard library's included: which of them
         *  are this module's is the law's question, answered from its own census. */
        val GOLDEN_REPORTS: Map<String, String> = mapOf(
            SEALED to """
                {
                  "format": "splice.public-surface/2",
                  "sha256": "<sha256>",
                  "declarations": ["fix.lib.Bridge", "fix.lib.CtorOnly", "fix.lib.MemberOnly", "fix.lib.Sealed"],
                  "edges": {
                    "fix.lib.Bridge": ["fix.lib.Sealed"],
                    "fix.lib.CtorOnly": ["kotlin.Any"],
                    "fix.lib.MemberOnly": ["kotlin.Any"],
                    "fix.lib.Sealed": ["fix.lib.MemberOnly", "kotlin.Any"]
                  }
                }
            """.trimIndent(),
            "src/main/kotlin/Override.kt" to """
                {
                  "format": "splice.public-surface/2",
                  "sha256": "<sha256>",
                  "declarations": ["fix.lib.Base", "fix.lib.Covariant", "fix.lib.Port", "fix.lib.Store"],
                  "edges": {
                    "fix.lib.Base": ["kotlin.Any"],
                    "fix.lib.Covariant": ["fix.lib.Base"],
                    "fix.lib.Port": ["fix.lib.Base"],
                    "fix.lib.Store": ["fix.lib.Covariant"]
                  }
                }
            """.trimIndent(),
            "src/main/kotlin/Enum.kt" to """
                {
                  "format": "splice.public-surface/2",
                  "sha256": "<sha256>",
                  "declarations": ["fix.lib.EnumOnly", "fix.lib.Mode"],
                  "edges": {
                    "fix.lib.EnumOnly": ["kotlin.Any"]
                  }
                }
            """.trimIndent(),
            ALIAS to """
                {
                  "format": "splice.public-surface/2",
                  "sha256": "<sha256>",
                  "declarations": ["fix.lib.Alias", "fix.lib.Ann", "fix.lib.Text"],
                  "edges": {
                    "fix.lib.Alias": ["kotlin.Int"],
                    "fix.lib.Ann": ["kotlin.String"],
                    "fix.lib.Text": ["kotlin.Any"]
                  }
                }
            """.trimIndent(),
        )

        /** Store.kt names Target, which Alias.kt expands to a class Types.kt declares: three files, one edge. */
        val ALONE_SOURCES: Map<String, String> = mapOf(
            "src/main/kotlin/Types.kt" to "package fix.lib\n\npublic class One\n\npublic class Two",
            ALIAS to "package fix.lib\n\npublic typealias Target = One",
            STORE to "package fix.lib\n\npublic class Store {\n    public fun read(): Target? = null\n}",
        )
    }
}
```

**PublicSurfaceIncrementalTest.kt, new in v3.** It is the required automatic test for the second correction. It runs real incremental compilation through the Build Tools API, the engine KGP drives, in this JVM. Each test runs two arms in its TempDir. The registered arm loads the shipped jar. The unregistered arm loads a classpath entry, written into the TempDir, whose service files name UnregisteredLookupsRegistrar below. That registrar differs from the shipped one only in passing a `SurfaceLookups` that records nothing. So each test shows itself able to fail with the registration removed: the unregistered arm must leave User's report naming Old, while a whole compile names New. No repo file is mutated.

```kotlin
// NEW (V4-92, v3): the reports under real incremental compilation. A report reads classes its source never names: the
// bound of a type parameter two star projections away, and an alias in that bound. SurfaceLookups records each one as
// a lookup of the source, so changing it recompiles the source and rewrites its report. Each test drives the Build
// Tools API's snapshot-based incremental compilation, the engine KGP runs, through a first build and one edit, twice:
// with the plugin as it ships, and with UnregisteredLookupsRegistrar, the same report extension with the recording
// removed. The first arm must end where a whole compile of the new text ends. The second must not, which is how each
// test fails when the registration goes. Every file the test writes is under its TempDir.
@file:OptIn(ExperimentalBuildToolsApi::class)

package splice.firchecks

import org.jetbrains.kotlin.buildtools.api.BuildOperation
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPluginOption
import org.jetbrains.kotlin.buildtools.api.jvm.JvmIncrementalCompilationConfiguration
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class PublicSurfaceIncrementalTest(@param:TempDir private val workDir: Path) {
    private val toolchains = KotlinToolchains.loadImplementation(PublicSurfaceIncrementalTest::class.java.classLoader)
    private val jvm = toolchains.getToolchain(JvmPlatformToolchain::class.java)
    private val stdlib: Path = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
    private val stdlibSnapshot: Path = workDir.resolve("kotlin-stdlib.snapshot")
    private val shipped: Path = Path.of(
        requireNotNull(System.getProperty("splice.firChecksPluginJar")) {
            "system property splice.firChecksPluginJar (set by the test task) is missing"
        },
    )

    init {
        val snapshot = execute(jvm.classpathSnapshottingOperationBuilder(stdlib).build(), CollectingLogger())
        snapshot.saveSnapshot(stdlibSnapshot)
    }

    @Test
    fun `a bound two star projections away is a lookup - changing it rewrites the report that reads it`() {
        assertRecorded(STAR_ROUTE, MID, "package fix.lib\n\npublic open class Mid<T : New>")
    }

    @Test
    fun `an alias in that bound is a lookup - retargeting it rewrites the report that reads it`() {
        assertRecorded(ALIAS_ROUTE, BOUND, "package fix.lib\n\npublic typealias Bound = New")
    }

    /** Builds [sources], rewrites [edited] to [text], and builds again incrementally, once per arm. Types.kt reads
     *  nothing that changes, so its report staying untouched shows that each second compile was incremental. */
    private fun assertRecorded(sources: Map<String, String>, edited: String, text: String) {
        val whole = Module(workDir.resolve("whole"), shipped).apply { write(sources + (edited to text)) }.compile()
        assertTrue(whole.getValue(USER_REPORT).contains(USER_NEW), whole.getValue(USER_REPORT))
        val registered = rebuild("registered", shipped, sources, edited, text)
        assertTrue(USER_REPORT in registered.rewritten, "User.kt was recompiled: ${registered.rewritten}")
        assertFalse(TYPES_REPORT in registered.rewritten, "Types.kt was not: ${registered.rewritten}")
        assertEquals(whole, registered.reports, "the incremental set equals a whole compile of the new text")
        val unregistered = rebuild("unregistered", unregisteredPlugin(), sources, edited, text)
        assertTrue("$edited.json" in unregistered.rewritten, "the edit was compiled: ${unregistered.rewritten}")
        assertFalse(USER_REPORT in unregistered.rewritten, "without the record, nothing recompiles User.kt")
        assertFalse(TYPES_REPORT in unregistered.rewritten, "Types.kt was not: ${unregistered.rewritten}")
        val stale = unregistered.reports.getValue(USER_REPORT)
        assertTrue(stale.contains(USER_OLD), stale)
        assertNotEquals(whole, unregistered.reports, "so the incremental set is not a whole compile's")
    }

    private data class Rebuild(val rewritten: Set<String>, val reports: Map<String, String>)

    /** A first build of [sources] under [arm], every report aged, [edited] rewritten to [text], and the incremental
     *  build that edit alone starts. */
    private fun rebuild(
        arm: String,
        plugin: Path,
        sources: Map<String, String>,
        edited: String,
        text: String,
    ): Rebuild {
        val module = Module(workDir.resolve(arm), plugin)
        module.write(sources)
        module.compile(SourcesChanges.Unknown)
        module.age()
        module.write(mapOf(edited to text))
        val reports = module.compile(SourcesChanges.Known(listOf(module.file(edited)), emptyList()))
        return Rebuild(module.rewritten(), reports)
    }

    /** A classpath entry holding only service files, which name UnregisteredLookupsRegistrar. The compiler's plugin
     *  loader reads services from the entry alone and loads classes through its parent, the class loader that loaded
     *  the compiler, which in this JVM also holds this module's main and test classes. */
    private fun unregisteredPlugin(): Path {
        val entry = workDir.resolve("unregistered-plugin")
        val services = Files.createDirectories(entry.resolve("META-INF/services"))
        Files.writeString(services.resolve(REGISTRAR_SERVICE), "${UnregisteredLookupsRegistrar::class.java.name}\n")
        Files.writeString(services.resolve(PROCESSOR_SERVICE), "${FirChecksCommandLineProcessor::class.java.name}\n")
        return entry
    }

    private fun <R> execute(operation: BuildOperation<R>, logger: KotlinLogger): R =
        toolchains.createBuildSession().use { session ->
            session.executeOperation(operation, toolchains.createInProcessExecutionPolicy(), logger)
        }

    /** One fixture module under [root]: sources and reports under lib/, classes, and the incremental caches. */
    private inner class Module(private val root: Path, private val plugin: Path) {
        private val dir = root.resolve("lib")
        private val reportDir = dir.resolve("build/reports").toFile()
        private val paths = sortedSetOf<String>()

        fun write(sources: Map<String, String>) {
            for ((path, text) in sources) {
                val source = dir.resolve(path)
                Files.createDirectories(source.parent)
                Files.writeString(source, "$text\n")
                paths += path
            }
        }

        fun file(path: String): File = dir.resolve(path).toFile()

        /** A whole compile, or with [changes] the snapshot-based incremental compile KGP runs. Returns every report. */
        fun compile(changes: SourcesChanges? = null): Map<String, String> {
            val sources = paths.map { dir.resolve(it) }
            val builder = jvm.jvmCompilationOperationBuilder(sources, Files.createDirectories(root.resolve("classes")))
            builder.compilerArguments.applyArgumentStrings(ARGUMENTS + listOf("-classpath", stdlib.toString()))
            builder.compilerArguments.set(CommonCompilerArguments.COMPILER_PLUGINS, listOf(compilerPlugin()))
            if (changes != null) {
                builder.set(JvmCompilationOperation.INCREMENTAL_COMPILATION, incremental(builder, changes))
            }
            val log = CollectingLogger()
            assertEquals(CompilationResult.COMPILATION_SUCCESS, execute(builder.build(), log), log.text())
            return reports()
        }

        /** Marks every report as older than the next compile: its modification time goes to the epoch. */
        fun age() {
            reportFiles().forEach { check(it.setLastModified(0)) { "$it: its modification time could not be set" } }
        }

        /** The reports the last compile wrote, told apart by the modification time [age] reset. */
        fun rewritten(): Set<String> = reportFiles().filter { it.lastModified() != 0L }.mapTo(sortedSetOf(), ::key)

        private fun reports(): Map<String, String> = reportFiles().associate { key(it) to it.readText() }

        private fun reportFiles(): List<File> = reportDir.walkTopDown().filter { it.isFile }.toList()

        private fun key(report: File): String = report.relativeTo(reportDir).invariantSeparatorsPath

        private fun compilerPlugin(): CompilerPlugin = CompilerPlugin(
            PLUGIN_ID,
            listOf(plugin),
            listOf(
                CompilerPluginOption(reportDirOption.optionName, reportDir.path),
                CompilerPluginOption(sourceRootOption.optionName, dir.toString()),
            ),
            emptySet(),
        )

        private fun incremental(
            builder: JvmCompilationOperation.Builder,
            changes: SourcesChanges,
        ): JvmIncrementalCompilationConfiguration = builder.snapshotBasedIcConfigurationBuilder(
            Files.createDirectories(root.resolve("ic")),
            changes,
            listOf(stdlibSnapshot),
            root.resolve("ic/shrunk-classpath.snapshot"),
        ).build()
    }

    /** Keeps what the compiler says, so a failed compile shows why. */
    private class CollectingLogger : KotlinLogger {
        private val lines = StringBuilder()

        override val isDebugEnabled: Boolean = false

        override fun error(msg: String, throwable: Throwable?) {
            lines.appendLine("e: $msg${throwable?.let { " ($it)" }.orEmpty()}")
        }

        override fun warn(msg: String, throwable: Throwable?) {
            lines.appendLine("w: $msg")
        }

        override fun info(msg: String) {
            lines.appendLine("i: $msg")
        }

        override fun debug(msg: String) = Unit

        override fun lifecycle(msg: String) {
            lines.appendLine(msg)
        }

        fun text(): String = lines.toString()
    }

    private companion object {
        const val TYPES = "src/main/kotlin/Types.kt"
        const val BOUND = "src/main/kotlin/Bound.kt"
        const val MID = "src/main/kotlin/Mid.kt"
        const val USER = "src/main/kotlin/User.kt"
        const val TYPES_REPORT = "$TYPES.json"
        const val USER_REPORT = "$USER.json"
        const val REGISTRAR_SERVICE = "org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
        const val PROCESSOR_SERVICE = "org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor"
        const val USER_OLD = "\"fix.lib.User\": [\"fix.lib.Box\", \"fix.lib.Mid\", \"fix.lib.Old\", \"kotlin.Any\"]"
        const val USER_NEW = "\"fix.lib.User\": [\"fix.lib.Box\", \"fix.lib.Mid\", \"fix.lib.New\", \"kotlin.Any\"]"

        /** The arguments PublicSurfaceReportTest compiles with; the classpath is the standard library alone. */
        val ARGUMENTS: List<String> =
            listOf("-Xexplicit-api=strict", "-Werror", "-no-stdlib", "-no-reflect", "-module-name", "lib")

        /** User names only Box. Box's bound names Mid, and Mid's bound names Old: two star projections from User.
         *  Old, New and Mid are open, because a final upper bound is a warning and the compile runs with -Werror. */
        val STAR_ROUTE: Map<String, String> = mapOf(
            TYPES to "package fix.lib\n\npublic open class Old\n\npublic open class New",
            MID to "package fix.lib\n\npublic open class Mid<T : Old>",
            "src/main/kotlin/Box.kt" to "package fix.lib\n\npublic class Box<T : Mid<*>>",
            USER to "package fix.lib\n\npublic class User {\n    public fun read(): Box<*>? = null\n}",
        )

        /** The same chain, with Mid's bound spelled through an alias that Bound.kt declares. */
        val ALIAS_ROUTE: Map<String, String> = STAR_ROUTE + mapOf(
            BOUND to "package fix.lib\n\npublic typealias Bound = Old",
            MID to "package fix.lib\n\npublic open class Mid<T : Bound>",
        )
    }
}
```

**UnregisteredLookupsRegistrar.kt, new in v3, test source.** It is the shipped registrar with the lookup recording removed and nothing else. PublicSurfaceIncrementalTest loads it as a plugin only from a classpath entry in its TempDir, so no compile outside that test sees it.

```kotlin
// NEW (V4-92, v3): MustConsumeCompilerPluginRegistrar with one thing removed, the lookups SurfaceLookups records.
// PublicSurfaceIncrementalTest loads it as a plugin to show that without them an incremental compile leaves a
// public-surface report stale.
package splice.firchecks

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar.ExtensionStorage
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import java.io.File

@OptIn(ExperimentalCompilerApi::class)
internal class UnregisteredLookupsRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(MustConsumeFirExtensionRegistrar())
        val reportDir = checkNotNull(configuration.get(reportDirKey)) {
            "the test passes ${reportDirOption.optionName}"
        }
        val sourceRoot = checkNotNull(configuration.get(sourceRootKey)) {
            "the test passes ${sourceRootOption.optionName}"
        }
        IrGenerationExtension.registerExtension(
            PublicSurfaceReportExtension(File(reportDir), File(sourceRoot)) { SurfaceLookups { } },
        )
    }
}
```

**quality/compiler-plugin/build.gradle.kts, v3.** Insert two lines directly after `testImplementation(libs.kotlin.compiler.embeddable)`.

```kotlin
    // V4-92: PublicSurfaceIncrementalTest drives the snapshot-based incremental compilation KGP runs, through the Build
    // Tools API in this JVM. The impl is runtime-only and brings the compiler runner; both are the toolchain's version.
    testImplementation(libs.kotlin.build.tools.api)
    testRuntimeOnly(libs.kotlin.build.tools.impl)
```

**gradle/libs.versions.toml, v3.** Insert two lines directly after the `kotlin-compiler-embeddable` line. gradle/verification-metadata.xml already pins both modules at 2.3.21, with every module they depend on, so catalogMetadataSync stays green and the metadata is not edited.

```toml
# The Build Tools API, the engine KGP drives for incremental compilation: PublicSurfaceIncrementalTest runs it in-process.
kotlin-build-tools-api = { module = "org.jetbrains.kotlin:kotlin-build-tools-api", version.ref = "kotlin" }
kotlin-build-tools-impl = { module = "org.jetbrains.kotlin:kotlin-build-tools-impl", version.ref = "kotlin" }
```

**build.gradle.kts.** Replace the `val firChecksPluginJar = ...` declaration with the block below, keeping the same expression. Then change the first `subprojects {}` block as shown. The existing lines inside `configureEach` stay as they are.

```kotlin
val firChecksPluginJar by extra(
    project(":quality-compiler-plugin").layout.buildDirectory
        .file("libs/fir-checks.jar"),
)
val firChecksPluginArg = firChecksPluginJar.map { "-Xplugin=${it.asFile.absolutePath}" }
// V4-92: where each module's compileKotlin writes one public-surface report per main source, module-relative. Written
// ONCE, here; quality/architecture reads it off this extra and hands it to the law.
val publicSurfaceReports by extra("build/splice/public-surface/main")

subprojects {
    // :quality-compiler-plugin must NOT compile against its own not-yet-built jar (self-application deadlock).
    if (path == ":quality-compiler-plugin") return@subprojects
    val moduleDir = layout.projectDirectory.asFile
    val reportDir = layout.projectDirectory.dir(publicSurfaceReports).asFile
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.withType<KotlinCompile>().configureEach {
            // ... the existing dependsOn, inputs.files(firChecksPluginJar) and -Xplugin lines, unchanged ...
            // V4-92: main only. testFixtures, test and lawFixtures compiles are not a library's surface.
            if (name == "compileKotlin") {
                // Incremental compilation stays ON. The plugin writes one report per source it compiles, and a report
                // depends only on its source's text and the ABI that source resolves against, so the files Kotlin
                // recompiles are exactly the reports that can change. A deleted or renamed source compiles nothing, so
                // its report is removed here, from this task's own source set, before the compiler runs.
                outputs.dir(reportDir).withPropertyName("publicSurfaceReports")
                val compiled = sources
                doFirst {
                    val kept = compiled.files.filter { it.extension == "kt" }
                        .mapTo(HashSet()) { "${it.relativeTo(moduleDir).invariantSeparatorsPath}.json" }
                    reportDir.walkBottomUp()
                        .filter { it.isFile && it.relativeTo(reportDir).invariantSeparatorsPath !in kept }
                        .forEach { stale -> check(stale.delete()) { "could not delete the stale report $stale" } }
                }
                compilerOptions.freeCompilerArgs.addAll(
                    "-P",
                    "plugin:splice.fir-checks:publicSurfaceReportDir=${reportDir.absolutePath}",
                    "-P",
                    "plugin:splice.fir-checks:publicSurfaceSourceRoot=${moduleDir.absolutePath}",
                )
            }
        }
    }
}
```

**quality/architecture/build.gradle.kts.** There are four edits, each anchored on content in the current file.

```kotlin
// 1. directly after the plugins {} block
// V4-92: the build's own Kotlin compiler, resolved apart from the test classpath. Konsist pins this JVM's compiler at
// 2.0.21 (testCompileOnly below), so PublicSurfaceLawTest's fixture compiles load this one in their own class loader
// (FixtureCompiler.kt). kotlin-stdlib also publishes JS, Wasm and native variants; the attributes ask for JVM jars.
val fixtureCompiler: Configuration = configurations.create("fixtureCompiler") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(
            TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
            objects.named(TargetJvmEnvironment.STANDARD_JVM),
        )
    }
}

// 2. first line inside the existing dependencies {}
    fixtureCompiler(libs.kotlin.compiler.embeddable)

// 3. directly after `val declaredReadRoots: List<String> = ...`
// V4-92: THE PUBLIC-SURFACE REPORTS the law reads beside the sources. The root build has every module's compileKotlin
// but the plugin's own write one report per main source under this module-relative directory, a declared output of
// that task. Here each directory is an input of this task, its compile runs first, and it joins the declared reads.
val publicSurfaceReports: String by rootProject.extra
val firChecksPluginJar: Provider<RegularFile> by rootProject.extra
val reportModules: List<String> = moduleDirectories.keys.filter { it != ":quality-compiler-plugin" }
val reportDirs: List<String> = reportModules.map { "${moduleDirectories.getValue(it)}/$publicSurfaceReports" }

// 4. inside tasks.withType<Test>().configureEach, replace the splice.declaredReadRoots systemProperty line with:
    systemProperty("splice.declaredReadRoots", (declaredReadRoots + reportDirs).joinToString(";"))
    // V4-92: the reports, the compiles that write them, and what the law's fixture trees compile with. The jar is
    // hashed raw, as the root build hashes it for every compile: the compiler runs the whole plugin, not its ABI.
    systemProperty("splice.publicSurfaceReports", publicSurfaceReports)
    dependsOn(reportModules.map { "$it:compileKotlin" })
    dependsOn(":quality-compiler-plugin:jar")
    inputs.files(reportDirs.map { repoRoot.dir(it) }).withPropertyName("publicSurfaceReports")
    inputs.files(firChecksPluginJar).withPropertyName("firChecksPluginJar")
    inputs.files(fixtureCompiler).withNormalizer(ClasspathNormalizer::class.java).withPropertyName("fixtureCompiler")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsplice.fixtureCompilerClasspath=${fixtureCompiler.files.joinToString(File.pathSeparator)}",
                "-Dsplice.firChecksPluginJar=${firChecksPluginJar.get().asFile.absolutePath}",
            )
        },
    )
```

The existing `declaredReadRoots` fingerprint of `**/*.kt` stays on `declaredReadRoots` alone. The report directories get their own input.

**FixtureCompiler.kt**

```kotlin
// NEW: (V4-92) compiles a law's synthetic tree with the build's own Kotlin compiler and the built fir-checks
// plugin, so a fixture is code the compiler accepts and its public-surface reports are the compiler's answer.
package splice.quality

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.reflect.Method
import java.net.URLClassLoader

/** Konsist runs this JVM's own Kotlin compiler at 2.0.21, so the build's compiler cannot share its loader. It gets
 *  its own, parented on the platform loader, and runs through K2JVMCompiler.exec by reflection. Both paths come
 *  from quality/architecture/build.gradle.kts, which declares them as inputs of this task. */
internal object FixtureCompiler {
    const val CLASSPATH_PROPERTY = "splice.fixtureCompilerClasspath"
    const val PLUGIN_PROPERTY = "splice.firChecksPluginJar"

    private val classpath: List<File> = property(CLASSPATH_PROPERTY).split(File.pathSeparator).map(::File)
    private val stdlib: File = classpath.single { it.name.startsWith("kotlin-stdlib-") }
    private val plugin: File = File(property(PLUGIN_PROPERTY))
    private val loader = URLClassLoader(
        classpath.map { it.toURI().toURL() }.toTypedArray(),
        ClassLoader.getPlatformClassLoader(),
    )
    private val compiler: Class<*> = loader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    private val exec: Method = compiler.getMethod("exec", PrintStream::class.java, Array<String>::class.java)

    /** Compiles [map] the way the build does, or returns the first failure's messages. Mains go in module-path
     *  order, each against the ones before it, under explicit API and with the report options, each a WHOLE
     *  compile that starts from no reports. testFixtures go after, against every main, with neither, which is how
     *  the build compiles them. */
    fun build(map: ProjectMap): String? {
        val mains = mutableListOf<File>()
        val main = sourceSets(map, PublicSurface.MAIN).firstNotNullOfOrNull { (module, sources) ->
            val out = File(map.dir(module), "build/classes/main")
            val reports = SurfaceReports.dir(map, module).apply { deleteRecursively() }
            compile(module, sources, mains.toList(), out, reports to map.dir(module)).also { mains += out }
        }
        return main ?: sourceSets(map, PublicSurface.TEST_FIXTURES).firstNotNullOfOrNull { (module, sources) ->
            compile("$module-testFixtures", sources, mains, File(map.dir(module), "build/classes/testFixtures"), null)
        }
    }

    private fun sourceSets(map: ProjectMap, sourceSet: String): List<Pair<String, List<File>>> =
        map.modules.sorted().map { it to PublicSurface.sources(map, it, sourceSet) }.filter { it.second.isNotEmpty() }

    /** null when it compiled; otherwise the exit code and the compiler's own messages, prefixed with [module]. */
    @Synchronized
    private fun compile(
        module: String,
        sources: List<File>,
        deps: List<File>,
        out: File,
        surface: Pair<File, File>?,
    ): String? {
        val asMain = surface?.let { (reports, root) ->
            listOf(
                "-Xexplicit-api=strict",
                "-P",
                "plugin:splice.fir-checks:publicSurfaceReportDir=${reports.absolutePath}",
                "-P",
                "plugin:splice.fir-checks:publicSurfaceSourceRoot=${root.absolutePath}",
            )
        }
        val args = listOf("-Xplugin=${plugin.absolutePath}") + asMain.orEmpty() + listOf(
            "-Werror", "-no-stdlib", "-no-reflect", "-module-name", module.removePrefix(":"),
            "-classpath", (listOf(stdlib) + deps).joinToString(File.pathSeparator),
            "-d", out.absolutePath,
        ) + sources.map { it.absolutePath }
        val buffer = ByteArrayOutputStream()
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = loader
        val exit = try {
            PrintStream(buffer, true, Charsets.UTF_8).use { stream ->
                exec.invoke(compiler.getConstructor().newInstance(), stream, args.toTypedArray())
            }
        } finally {
            thread.contextClassLoader = previous
        }
        return if (exit.toString() == "OK") null else "$module: $exit\n${buffer.toString(Charsets.UTF_8)}"
    }

    private fun property(name: String): String = checkNotNull(System.getProperty(name)) {
        "$name is not set: quality/architecture/build.gradle.kts hands it to this JVM, so this ran outside " +
            "the task that declares the compiler and the plugin as its inputs"
    }
}
```

**PublicSurfaceLawTest.kt, edits from HEAD.**
1. **Header, PARSE paragraph.** Keep the line ending "Justification then PROPAGATES through". Replace the following lines, through "the union over overloads is deliberate.", with the first block below.
2. **Header, NOT CAUGHT paragraph.** Replace the line ending "this paragraph is the marker." with the second block below.
3. **Imports.** Delete the sixteen `com.lemonappdev.konsist` imports. Add `kotlinx.serialization.json.JsonElement`, `kotlinx.serialization.json.jsonArray`, `kotlinx.serialization.json.jsonPrimitive` and `java.security.MessageDigest`.
4. **Top-level functions.** Delete `signatureOf`, `isPublic`, `extensible`, `reachable`, `contractOf`, `parentType` and `constructorTypes`, with their KDoc.
5. **In `PublicSurface`.** Delete `IDENT`, the `signature` field of `Declaration`, and `KONSIST_ROOT`. Replace `declarations` and `declarationsIn`, insert the report read into `unjustified`, and replace `closeOver` and `reach`, all as shown below.
6. **New object.** Add `internal object SurfaceReports` directly before `class PublicSurfaceLawTest`.
7. **Live test.** Add its two declared-read lines.
8. **Tree.** Make `synthetic` a public `val`, and make `write` compile the tree.
9. **Integrity test.** Replace the two tests "what a subclass or an alias reaches is contract" and "a supertype call's arguments are not contract" with it. Rows c01 to c05 now cover both.
10. **Near-miss test.** Write `LIB_API_OTHER to API_OTHER` beside `LIB_API to API`.
11. **Constants.**
    - Add `LIB_API_OTHER` and `API_OTHER`.
    - `LEAK` becomes `public val v`.
    - `APP_SOURCE` becomes `internal class M`.
    - `LAMBDA_STORE` gets `public val` on both properties.
    - Delete `PROTECTED_CONSTRUCTOR_STORE`, `SECONDARY_CONSTRUCTOR_STORE`, `PROTECTED_MEMBER_STORE`, `ALIAS_STORE` and `SUPER_CALL_STORE`.

```kotlin
// reachable signatures to a fixpoint, and what a signature reaches is the COMPILER's answer, never
// this file's: quality/compiler-plugin's PublicSurfaceWalk reads every declaration the way
// FirExposedVisibilityDeclarationChecker does, through the same EffectiveVisibility, and compileKotlin
// writes ONE REPORT PER SOURCE it compiles (build/splice/public-surface/main/<source path>.json), a
// declared output, with incremental compilation on. An edge `Store -> Hidden` means: were Hidden
// internal, Store's module would fail with EXPOSED_*. A report names every class a signature names, from
// any module, and only the edges into a declaration's OWN module are followed. So a sealed class's
// constructor, an enum's implicit private one, an argument of a supertype call, a default value, a
// delegation expression and a typealias's annotation are not contract, and a protected member of a sealed
// or open class, an implicit public override and an alias's EXPANDED target are. Three of those were this
// law's own defects while it re-derived visibility from text.
// THE REPORTS ARE REFUSED BY NAME, not trusted: a main source with no report, a report whose source is
// gone, a report written for other text than its source now holds (so the compile that saw that text ran
// without the plugin), a report that does not parse, a report with an edge owner it does not declare
// (the plugin only writes edges from declarations it declares, so the closure never skips an owner), and
// a joined set of public top-level names that differs from the line census below: two independent
// censuses, one compiler and one parser, must agree before either grades anything.
```

```kotlin
// modules, so the hole is empty today and this paragraph is the marker. A PUBLIC INLINE function's
// body is not a signature, so a type named only there reads as unjustified; the compiler says so too
// (PRIVATE_CLASS_MEMBER_FROM_INLINE), and making it internal is the same test. A top-level public
// extension or generic function (`public fun <T> x`, `public fun A.x`) is misread by the line census;
// none exists today, and the census check refuses BY NAME the day one lands, so it cannot pass in silence.
```

```kotlin
    // in PublicSurface: the census, now parser-only
    /** Every `public` line at column 0 of the module's main sources: the parser's census, which the
     *  compiler's report must equal before either is trusted. */
    private fun declarations(map: ProjectMap, module: String): List<Declaration> =
        sources(map, module, MAIN).flatMap { file ->
            declarationsIn(module, KotlinText.rel(map, file), file.readText())
        }

    private fun declarationsIn(module: String, rel: String, text: String): List<Declaration> {
        val pkg = PACKAGE.find(text)?.groupValues?.get(1).orEmpty()
        val lines = text.split(LINE_SPLIT)
        return lines.indices.mapNotNull { index ->
            DECLARATION.find(lines[index])?.let { match ->
                Declaration(module, pkg, match.groupValues[2], match.groupValues[1], rel, index + 1)
            }
        }
    }

    // in unjustified, after `if (refusal.isNotEmpty()) return Surface(emptyList(), 0, refusal)`
        val reports = libraries.associateWith { module ->
            val census = all.filter { it.module == module }.mapTo(mutableSetOf()) { it.fqn }
            SurfaceReports.read(map, module, sources(map, module, MAIN), census)
        }
        val untrusted = reports.values.flatMap { it.second }
        if (untrusted.isNotEmpty()) return Surface(emptyList(), 0, untrusted)
        val justified = justify(all, map.modules.sorted().associateWith { consumerOf(map, it) })
        closeOver(all, reports.mapValues { it.value.first.edges }, justified)

    // replaces closeOver and reach
    /** THE CLOSURE, off the compiler's edges and iterated to a fixpoint: a chain (consumed type -> parameter
     *  type -> field type) is the same argument applied twice. A report names every class a signature names, so
     *  an edge is followed only into its owner's OWN module; its target is a top-level FQN, so a nested class's
     *  reach is its outermost declaration's. */
    private fun closeOver(
        all: List<Declaration>,
        edges: Map<String, Map<String, Set<String>>>,
        justified: MutableSet<String>,
    ) {
        val byModule = all.groupBy { it.module }
        var frontier = all.filter { it.fqn in justified }
        while (frontier.isNotEmpty()) {
            frontier = frontier.flatMap { owner ->
                val reached = edges[owner.module]?.get(owner.fqn).orEmpty()
                byModule.getValue(owner.module).filter { it.fqn in reached && justified.add(it.fqn) }
            }
        }
    }
```

```kotlin
/** The compiler's reports for one library module, one per main source, joined into one; or why they cannot be
 *  trusted, each reason naming the file it is about. */
internal object SurfaceReports {
    /** The format fir-checks' PublicSurfaceReport.kt writes, and the property naming where it writes: the
     *  module-relative directory the root build hands both compileKotlin and this JVM, so writer and reader share one. */
    const val FORMAT = "splice.public-surface/2"
    const val PROPERTY = "splice.publicSurfaceReports"

    /** One source as the compiler measured it: the SHA-256 of the text it compiled, the source's public top-level
     *  declarations, and for each one the classes a reachable signature of it names. Joined, [sha256] is empty. */
    data class Report(val sha256: String, val declarations: Set<String>, val edges: Map<String, Set<String>>) {
        companion object {
            /** Throws on anything that is not [FORMAT]; the caller turns that into one refusal. An edge owner the
             *  report does not declare is not [FORMAT] either: the plugin writes an edge only from a public top-level
             *  declaration of the same source, which it always declares. Skipping such an owner would drop its
             *  edges and grade what they reach unjustified. */
            fun parse(text: String): Report {
                val document = Json.parseToJsonElement(text).jsonObject
                check(document["format"] == JsonPrimitive(FORMAT)) { "format is ${document["format"]}" }
                val sha256 = document.getValue("sha256").jsonPrimitive.also { check(it.isString) }.content
                val declarations = strings(document.getValue("declarations"))
                val edges = document.getValue("edges").jsonObject.mapValues { (_, targets) -> strings(targets) }
                val undeclared = (edges.keys - declarations).sorted()
                check(undeclared.isEmpty()) { "edges name owners $undeclared, which it does not declare" }
                return Report(sha256, declarations, edges)
            }

            private fun strings(element: JsonElement): Set<String> =
                element.jsonArray.map { item -> item.jsonPrimitive.also { check(it.isString) }.content }.toSet()
        }
    }

    private val NONE = Report("", emptySet(), emptyMap())

    /** Where [module]'s compileKotlin writes its reports. */
    fun dir(map: ProjectMap, module: String): File =
        File(map.dir(module), checkNotNull(System.getProperty(PROPERTY)) { "$PROPERTY is not set" })

    /** [source]'s report: [dir], then the source's path relative to its module, then `.json`. */
    fun of(map: ProjectMap, module: String, source: File): File =
        File(dir(map, module), "${source.relativeTo(map.dir(module)).invariantSeparatorsPath}.json")

    /** [module]'s reports joined, or every reason they cannot be trusted. [sources] are its main sources and
     *  [census] the public FQNs the parser read in them. */
    fun read(map: ProjectMap, module: String, sources: List<File>, census: Set<String>): Pair<Report, List<String>> {
        val dir = dir(map, module)
        val bySource = sources.associateBy { of(map, module, it).relativeTo(dir).invariantSeparatorsPath }
        val written = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toSet()
        val orphans = (written - bySource.keys).sorted().map { key ->
            "${KotlinText.rel(map, File(dir, key))}: a report for ${key.removeSuffix(".json")}, which is not a main " +
                "source of $module. compileKotlin deletes the report of every source it no longer compiles before it " +
                "compiles, so this one outlived a deleted or renamed file without that task running"
        }
        val missing = bySource.filterKeys { it !in written }.values.map { KotlinText.rel(map, it) }.sorted()
        val unwritten = missing.takeIf { it.isNotEmpty() }?.let {
            "${KotlinText.rel(map, dir)}: no report for $it. compileKotlin writes one for every main source it " +
                "compiles, through the fir-checks plugin (-P plugin:splice.fir-checks:publicSurfaceReportDir); a " +
                "compiler without that plugin ignores the option in silence, which is why this refuses rather than " +
                "reading an empty contract"
        }
        val read = bySource.filterKeys { it in written }.map { (key, source) -> readOne(map, File(dir, key), source) }
        val problems = orphans + listOfNotNull(unwritten) + read.mapNotNull { it.second }
        if (problems.isNotEmpty()) return NONE to problems
        val reports = read.mapNotNull { it.first }
        val edges = reports.flatMap { it.edges.entries }.groupBy({ it.key }, { it.value })
            .mapValues { (_, targets) -> targets.flatten().toSet() }
        val joined = Report("", reports.flatMapTo(mutableSetOf()) { it.declarations }, edges)
        val uncompiled = (census - joined.declarations).sorted()
        val unparsed = (joined.declarations - census).sorted()
        return joined to listOfNotNull(
            uncompiled.takeIf { it.isNotEmpty() }
                ?.let { "${KotlinText.rel(map, dir)}: the compiler did not declare $it, which the parser read" },
            unparsed.takeIf { it.isNotEmpty() }
                ?.let { "${KotlinText.rel(map, dir)}: the parser did not enumerate $it, which the compiler declared" },
        )
    }

    /** One report, or why it is refused: it does not parse, it breaks the format (an edge owner it does not declare
     *  included), or it was written for other text than [source] holds. */
    private fun readOne(map: ProjectMap, report: File, source: File): Pair<Report?, String?> {
        val rel = KotlinText.rel(map, report)
        val parsed = runCatching { Report.parse(report.readText()) }
        val read = parsed.getOrNull() ?: return null to "$rel: not a $FORMAT report " +
            "(${parsed.exceptionOrNull()?.message}) — a report that breaks the format grades nothing"
        val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
        return if (read.sha256 == hash) {
            read to null
        } else {
            null to "$rel: written for other text than ${KotlinText.rel(map, source)} holds now. compileKotlin " +
                "rewrites a source's report every time it compiles that source, so the compile that saw this text ran " +
                "without the fir-checks plugin, or has not run"
        }
    }
}
```

```kotlin
    // live test: first two lines of `the unjustified public surface is exactly the recorded baseline - V4-92`
        val (exempt, _) = PublicSurface.nonLibrary(lawText())
        map.modules.filterNot { it in exempt }.forEach { declaredRead(SurfaceReports.dir(map, it)) }

    // Tree
        val synthetic = ProjectMap.parse(root, modules, setOf("build"))

        /** Writes the tree and COMPILES it with the plugin, so every fixture is code the compiler accepts. */
        fun write(vararg files: Pair<String, String>) {
            for (home in listOf("modules", "other", "gateway")) File(root, home).deleteRecursively()
            for ((rel, body) in files) File(root, rel).apply { parentFile.mkdirs() }.writeText(body)
            FixtureCompiler.build(synthetic)?.let { error("a fixture that does not compile proves nothing:\n$it") }
        }

    // the integrity test
    @Test
    fun `the law can actually fail - a report it cannot trust refuses - V4-92`(@TempDir root: File) {
        with(Tree(root)) {
            write(LIB_STORE to store("public"), OTHER_USE to STORE_USE)
            assertEquals(emptyList<String>(), audit(baseline()), "the compiled tree, with its reports, is green")
            val source = File(root, LIB_STORE)
            val report = SurfaceReports.of(synthetic, ":lib", source)
            val good = report.readText()
            report.delete()
            assertHit(audit(baseline()), "no report for [$LIB_STORE]") {
                "no report is a refusal, never an empty contract"
            }
            report.writeText(good.replace(SurfaceReports.FORMAT, "splice.public-surface/1"))
            assertHit(audit(baseline()), "Store.kt.json: not a splice.public-surface/2 report") {
                "another format is not this one"
            }
            report.writeText(good.replaceFirst("\"fix.lib.Hidden\", ", ""))
            assertHit(audit(baseline()), "the compiler did not declare [fix.lib.Hidden]") { "a partial report" }
            report.writeText(good.replaceFirst("\"fix.lib.Hidden\"", "\"fix.lib.Ghost\", \"fix.lib.Hidden\""))
            assertHit(audit(baseline()), "the parser did not enumerate [fix.lib.Ghost]") { "a census the parser lost" }
            report.writeText(good.replace("\"fix.lib.Store\": [", "\"fix.lib.Stone\": ["))
            assertHit(audit(baseline()), "Store.kt.json: not a splice.public-surface/2 report", "[fix.lib.Stone]") {
                "an edge owner the report does not declare is corruption, never a key the closure skips"
            }
            report.writeText(good)
            val orphan = File(report.parentFile, "Gone.kt.json").apply { writeText(good) }
            assertHit(audit(baseline()), "Gone.kt.json: a report for src/main/kotlin/Gone.kt, which is not a main") {
                "a deleted or renamed source's report is refused by name, never dropped"
            }
            orphan.delete()
            source.appendText("// edited after its compile\n")
            assertHit(audit(baseline()), "Store.kt.json: written for other text than $LIB_STORE") {
                "a report the last compile did not rewrite describes text that is gone"
            }
        }
    }

    // constants
        const val LIB_API_OTHER = "modules/lib/src/main/kotlin/ApiOther.kt"
        const val LEAK = "package fix.lib\npublic class SelftestLeak(public val v: Int)\n"
        const val API_OTHER = "package fix.lib\npublic class ApiOther\n"
        const val APP_SOURCE = "package fix.app\ninternal class M\n"
        const val LAMBDA_STORE = "package fix.lib\n\npublic class Store(\n    public val onFail: () -> Unit = { },\n" +
            "    public val hidden: Hidden,\n)\n\npublic class Hidden\n"
```

**PublicSurfaceContractTest.kt** is unchanged from v1. Its header and test class are below, followed by the 23 rows.

```kotlin
// NEW: (V4-92) the public-surface law's acceptance table. Every row is a shape a reviewer proved with two
// compiling modules (lib and its consumer). Each run grades the row with the law, and re-proves the row's own
// verdict with the compiler: the offenders made internal together still compile, and every other public
// declaration made internal ALONE does not.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val CONTRACT_MODULES = ":lib=modules/lib;:other=other"
private const val CONTRACT_LAW = "val nonLibrary = setOf()\n"
private const val LIB_SOURCE = "modules/lib/src/main/kotlin/Store.kt"
private const val USE_SOURCE = "other/src/main/kotlin/Use.kt"

/** One proven shape: :lib's source, :other's consumer, and the simple names the law must call offenders. */
private data class Shape(val name: String, val lib: String, val use: String, val offenders: Set<String>)

private val SHAPES: List<Shape> = listOf(/* the 23 rows below, each Shape(name, lib, use, setOf(offenders)) with lib and use as trimIndent raw strings */)

class PublicSurfaceContractTest {
    @Test
    fun `every proven shape grades as the compiler proves it - V4-92`(@TempDir root: File) {
        val wrong = SHAPES.flatMap { shape -> lawDisagrees(root, shape) + compilerDisagrees(root, shape) }
        assertEquals(emptyList<String>(), wrong, "the law and the compiler must agree with every row")
    }

    @Test
    fun `the oracle can actually fail - a wrong row is named - V4-92`(@TempDir root: File) {
        val sealed = SHAPES.single { it.name.startsWith("c11") }.copy(offenders = emptySet())
        assertHit(lawDisagrees(root, sealed), "c11", "law says [Hidden]") {
            "a row that claims the sealed protected constructor justifies Hidden must disagree with the law"
        }
        assertHit(compilerDisagrees(root, sealed), "c11", "Hidden compiles internal") {
            "the compiler must refute a row that calls an internal-able declaration justified"
        }
        val override = SHAPES.single { it.name.startsWith("c09") }.copy(offenders = setOf("Hidden", "Port"))
        assertHit(compilerDisagrees(root, override), "c09", "together does not compile") {
            "the compiler must refute a row that calls a reached declaration an offender"
        }
    }

    private fun write(root: File, lib: String, use: String): ProjectMap {
        for (home in listOf("modules", "other")) File(root, home).deleteRecursively()
        File(root, LIB_SOURCE).apply { parentFile.mkdirs() }.writeText("package fix.lib\n\n${lib.trimIndent()}\n")
        File(root, USE_SOURCE).apply { parentFile.mkdirs() }.writeText("package fix.other\n\n${use.trimIndent()}\n")
        return ProjectMap.parse(root, CONTRACT_MODULES, setOf("build"))
    }

    private fun lawDisagrees(root: File, shape: Shape): List<String> {
        val map = write(root, shape.lib, shape.use)
        FixtureCompiler.build(map)?.let { return listOf("${shape.name}: the row does not compile: $it") }
        val surface = PublicSurface.unjustified(map, CONTRACT_LAW)
        val said = surface.offenders.mapTo(sortedSetOf()) { it.name }
        return when {
            surface.problems.isNotEmpty() -> listOf("${shape.name}: untrusted ${surface.problems}")
            said != shape.offenders.toSortedSet() ->
                listOf("${shape.name}: law says $said, row says ${shape.offenders.sorted()}")
            else -> emptyList()
        }
    }

    private fun compilerDisagrees(root: File, shape: Shape): List<String> {
        val declared = shape.lib.trimIndent().lines()
            .mapNotNull { PublicSurface.DECLARATION.find(it)?.groupValues?.get(2) }
        val together = shape.offenders.fold(shape.lib) { source, name -> internal(source, name) }
        val out = mutableListOf<String>()
        if (shape.offenders.isNotEmpty() && FixtureCompiler.build(write(root, together, shape.use)) != null) {
            out += "${shape.name}: ${shape.offenders.sorted()} made internal together does not compile"
        }
        for (name in declared.filterNot { it in shape.offenders }) {
            if (FixtureCompiler.build(write(root, internal(shape.lib, name), shape.use)) == null) {
                out += "${shape.name}: $name compiles internal, so nothing justifies it"
            }
        }
        return out
    }

    /** [source] with the top-level declaration [name] made internal: the one edit the law recommends. */
    private fun internal(source: String, name: String): String {
        val kinds = "class|interface|object|fun|val|var|typealias"
        val line = Regex("^public(?=(?:[ \\t]+[a-z]+)*[ \\t]+(?:$kinds)[ \\t]+$name\\b)")
        val lines = source.trimIndent().lines()
        check(lines.count { line.containsMatchIn(it) } == 1) { "no single public line declares $name" }
        return lines.joinToString("\n") { line.replaceFirst(it, "internal") }
    }
}
```

**Acceptance rows c01 to c23.** Each row lists its lib source, its consumer source and its offenders. The names are exact.

```
c01 a protected constructor of an open class                      offenders: none
  lib: public open class Store protected constructor(hidden: Hidden?)
       public class Hidden
  use: import fix.lib.Store
       internal class Use: Store(null)
c02 a public secondary constructor                                offenders: none
  lib: public class Store { public constructor(hidden: Hidden?) }
       public class Hidden
  use: import fix.lib.Store
       internal fun use(): Store = Store(null)
c03 a protected member of an open class                           offenders: none
  lib: public open class Store { protected fun read(): Hidden = Hidden() }
       public class Hidden
  use: import fix.lib.Store
       internal class Use: Store() { fun use() = read() }
c04 the target of a public typealias, not the alias               offenders: Alias
  lib: public typealias Alias = Hidden
       public class Store { public fun read(): Alias = Hidden() }
       public class Hidden
  use: import fix.lib.Store
       internal fun use(store: Store) = store.read()
c05 only the arguments of a supertype call name it                offenders: Hidden
  lib: public open class Base(value: Any)
       public class Store : Base(Hidden())
       public class Hidden
  use: import fix.lib.Store
       internal class Use(val store: Store)
c06 only a delegation expression names it                         offenders: Hidden, Port
  lib: public interface Port { public fun read(): Int }
       public class Hidden: Port { public override fun read(): Int = 1 }
       public class Store: Port by Hidden()
  use: import fix.lib.Store
       internal fun use(store: Store): Int = store.read()
c07 a protected nested class's constructor                        offenders: none
  lib: public open class Store { protected class Nested(hidden: Hidden?) }
       public class Hidden
  use: import fix.lib.Store
       internal class Use: Store() { fun use() { Nested(null) } }
c08 defect 1: a protected member of a SEALED class, through its open subclass   offenders: none
  lib: public sealed class Store { protected fun read(): Hidden = Hidden() }
       public open class Bridge: Store()
       public class Hidden
  use: import fix.lib.Bridge
       internal class Use: Bridge() { fun use() = read() }
c09 defect 2: an implicitly public override with a covariant return            offenders: Port
  lib: public open class Base
       public interface Port { public fun read(): Base }
       public class Store: Port { override fun read(): Hidden = Hidden() }
       public class Hidden: Base() { public fun touch(): Int = 1 }
  use: import fix.lib.Store
       internal fun use(store: Store): Int = store.read().touch()
c10 defect 3: an enum's implicitly private constructor            offenders: Hidden
  lib: public enum class Store(private val hidden: Hidden) { FIRST(Hidden()) }
       public class Hidden
  use: import fix.lib.Store
       internal val use: Store = Store.FIRST
c11 control: a sealed class's protected constructor reaches nothing           offenders: Hidden
  lib: public sealed class Store protected constructor(hidden: Hidden?)
       public open class Bridge: Store(null)
       public class Hidden
  use: import fix.lib.Bridge
       internal class Use: Bridge()
c12 defect 4: an alias annotation's text is not its target       offenders: Alias, Hidden
  lib: @Target(AnnotationTarget.TYPEALIAS, AnnotationTarget.CLASS)
       public annotation class Ann(public val message: String)
       @Ann("ignored = Hidden")
       public typealias Alias = Int
       public class Store { public fun read(): Alias = 1 }
       public class Hidden
  use: import fix.lib.Store
       import fix.lib.Ann
       @Ann("client")
       internal class Use(val store: Store)
c13 a public member's return type                                 offenders: none
  lib: public class Store {
           public fun read(): Hidden = Hidden()
       }
       public class Hidden
  use: import fix.lib.Store
       internal class Use(val s: Store)
c14 an internal member's return type                              offenders: Hidden
  lib: public class Store {
           internal fun read(): Hidden = Hidden()
       }
       public class Hidden
  use: import fix.lib.Store
       internal class Use(val s: Store)
c15 a wrapped parameter list                                      offenders: none
  lib: public class Store {
           public fun read(
               flag: Boolean,
               hidden: Hidden,
           ): Int = 0
       }
       public class Hidden
  use: import fix.lib.Store
       internal class Use(val s: Store)
c16 consts named only in defaults                                 offenders: DEFAULT_BYTES, DEFAULT_LIMIT, DEFAULT_SIZE
  lib: public const val DEFAULT_SIZE: Int = 16
       public const val DEFAULT_BYTES: Int = 8
       public const val DEFAULT_LIMIT: Long = 30_000
       public class Store(size: Int = DEFAULT_SIZE) {
           public data class Policy(
               val bytes: Int = DEFAULT_BYTES,
               val limit: Long = maxOf(
                   DEFAULT_LIMIT,
                   1L,
               ),
               val hidden: Hidden,
           )
       }
       public class Hidden
  use: import fix.lib.Store
       internal class Use(val s: Store)
c17 a parameter after a default lambda                            offenders: none
  lib: public class Store(
           public val onFail: () -> Unit = { },
           public val hidden: Hidden,
       )
       public class Hidden
  use: import fix.lib.Store
       internal class Use(val s: Store)
c18 a public companion's member                                   offenders: none
  lib: public class Store { public companion object { public fun read(): Hidden = Hidden() } }
       public class Hidden
  use: import fix.lib.Store
       internal fun use() = Store.read()
c19 an interface a class implements is not contract               offenders: Port
  lib: public interface Port { public fun ping(): Int = 1 }
       public class Store : Port
  use: import fix.lib.Store
       internal fun use(store: Store): Int = store.ping()
c20 a type argument and a type-parameter bound                    offenders: none
  lib: public class Store {
           public fun read(): List<Hidden> = emptyList()
           public fun <T : Bound> pick(t: T): T = t
       }
       public class Hidden
       public open class Bound
  use: import fix.lib.Store
       internal fun use(store: Store): Int = store.read().size
c21 a type argument of an alias's target                          offenders: Alias
  lib: public typealias Alias = List<Hidden>
       public class Store { public fun read(): Alias = emptyList() }
       public class Hidden
  use: import fix.lib.Store
       internal fun use(store: Store): Int = store.read().size
c22 an interface's interface supertype                            offenders: none
  lib: public interface Port
       public interface Store : Port
  use: import fix.lib.Store
       internal fun use(store: Store): Any = store
c23 a protected member of a final class                           offenders: none
  lib: public class Store { protected fun read(): Hidden = Hidden() }
       public class Hidden
  use: import fix.lib.Store
       internal fun use(store: Store): Store = store
```

## Build sequence, red checks and risks

1. **Plugin.** Add PublicSurfaceReport.kt, PublicSurfaceWalk.kt, SurfaceLookups.kt, the registrar and the service file. The check below must pass with no detekt or ast-grep finding.
   ```
   ./gradlew :quality-compiler-plugin:check
   ```
2. **Plugin tests.** Add PublicSurfaceReportTest.kt, PublicSurfaceIncrementalTest.kt and UnregisteredLookupsRegistrar.kt, and make the v3 edits to quality/compiler-plugin/build.gradle.kts and gradle/libs.versions.toml. Expect 10 tests passing: 8 in PublicSurfaceReportTest and 2 in PublicSurfaceIncrementalTest. catalogMetadataSync must stay green with verification-metadata.xml unedited.
   - **PublicSurfaceReportTest's red check** is inside the test. "The golden can fail" opens the sealed class in the fixture text, and the report must differ and name CtorOnly. "A source compiled alone" and "a report changes only when its own source is compiled" are the tests that failed against v1's origin filter.
   - **PublicSurfaceIncrementalTest's red check** is its unregistered arm. Each test compiles the same first build and the same edit again, with the lookup recording removed through UnregisteredLookupsRegistrar. That arm must leave User's report naming Old and the report set unequal to a whole compile. The arm loads its registrar from service files the test writes into its TempDir, so no repo file changes.
   - **If an unregistered arm finds User's report rewritten,** the compiler already records that lookup and the test cannot fail for that route. Stop and report it with the arm's log; do not weaken the assertion.
   ```
   ./gradlew :quality-compiler-plugin:test --tests 'splice.firchecks.PublicSurfaceReportTest'
   ./gradlew :quality-compiler-plugin:test --tests 'splice.firchecks.PublicSurfaceIncrementalTest'
   ./gradlew catalogMetadataSync
   ```
3. **Root wiring.** Edit build.gradle.kts. Every library module must then have one report per main source. The loop must print nothing, and a second compile must be UP-TO-DATE.
   ```
   grep -c 'incremental = false' build.gradle.kts            # expect 0
   ./gradlew compileKotlin
   for m in $(git ls-files '*/src/main/kotlin/*.kt' | sed 's|/src/main/kotlin/.*||' | sort -u | grep -v '^quality/compiler-plugin$'); do
     s=$(find "$m/src/main/kotlin" -name '*.kt' | wc -l)
     r=$(find "$m/build/splice/public-surface/main" -name '*.kt.json' 2>/dev/null | wc -l)
     [ "$s" = "$r" ] || echo "MISMATCH $m sources=$s reports=$r"
   done
   ./gradlew :core:compileKotlin                             # expect UP-TO-DATE
   ```
4. **Architecture wiring.** Edit quality/architecture/build.gradle.kts. The `fixtureCompiler` configuration must list kotlin-compiler-embeddable 2.3.21. The dry run must list every `compileKotlin` and `:quality-compiler-plugin:jar`. The HEAD law must stay green.
   ```
   ./gradlew :quality-architecture:dependencies --configuration fixtureCompiler
   ./gradlew :quality-architecture:test --dry-run
   ./gradlew :quality-architecture:test --tests 'splice.quality.PublicSurfaceLawTest'
   ```
5. **Law.** Add FixtureCompiler.kt and make the PublicSurfaceLawTest.kt edits. Expect 11 passing law tests, including the live test and the integrity test, then the whole architecture suite green. The baseline must not change. The integrity test is the red check: it deletes, rewrites, orphans and edits only inside its TempDir. One of its rewrites is the Store-to-Stone control. It renames the owner key `fix.lib.Store` to `fix.lib.Stone` and keeps Hidden as its target, the source hash and the declarations, and the law must refuse Store.kt.json naming `[fix.lib.Stone]`. Before v3 that report drew no refusal and graded fix.lib.Hidden an offender.
   ```
   ./gradlew :quality-architecture:test --tests 'splice.quality.PublicSurfaceLawTest'
   ./gradlew :quality-architecture:test
   git diff --exit-code quality/architecture/src/test/resources/public-surface-baseline.json
   ```
6. **Contract.** Add PublicSurfaceContractTest.kt. Expect 2 passing tests with zero disagreements. The red check is "the oracle can actually fail". It copies rows c11 and c09 with wrong offenders inside the test, and both must be named. No file is edited.
   ```
   ./gradlew :quality-architecture:test --tests 'splice.quality.PublicSurfaceContractTest'
   ```
7. **Optional Gradle-level incremental check.** Run it only in a detached worktree outside the shared checkout, and remove the worktree afterwards. Pick any core main source for `F`. Expect only that source's report to be newer than the marker. After the rename, expect the old report to be gone and the new one present. The law must be green at the end.
   ```
   W=$(mktemp -d); git worktree add --detach "$W/wt" <commit holding steps 1-6>; cd "$W/wt"
   ./gradlew :core:compileKotlin
   F=src/main/kotlin/<path of a core main source>.kt; R=core/build/splice/public-surface/main
   touch "$W/mark"; sleep 1; printf '\n// ic probe\n' >> "core/$F"
   ./gradlew :core:compileKotlin; find "$R" -type f -newer "$W/mark"
   git mv "core/$F" "core/${F%.kt}Moved.kt"; ./gradlew :core:compileKotlin
   test ! -e "$R/$F.json" && test -e "$R/${F%.kt}Moved.kt.json" && echo renamed-ok
   ./gradlew :quality-architecture:test --tests 'splice.quality.PublicSurfaceLawTest'
   cd - && git worktree remove --force "$W/wt" && rm -rf "$W"
   ```

No red check edits the shared tree. Each one mutates fixture text or report files inside JUnit's TempDir, or uses a wrong row built inside the test.

**Risks.**
- **Gradle wiring never ran.** Its API calls are verified against the KGP jar, and steps 3 to 5 are its first execution.
- **Pre-commit cost.** The law suite now depends on every module's compileKotlin. After the first build, a one-file change costs that module's incremental compile plus classpath-snapshot checks downstream. This cost is not measured.
- **First build after landing.** It is non-incremental everywhere, because the compiler arguments change.
- **Build cache.** Absolute paths in the `-P` arguments make the compile cache key specific to one checkout. gradle.properties does not enable the build cache, so nothing changes today.
- **Paths under build/.** The report trees mirror `src/main/kotlin` under build/. The census sweep excludes `build`, and the `.kt.json` suffix matches no `**/*.kt` glob. The full suite run in step 5 is the check.
- **A .kt outside src/main/kotlin.** If compileKotlin compiled such a file, its report would be refused as "not a main source". Today git tracks none under src/main/java or src/main/resources. codemode's generated source is Java and is filtered out.
- **Residual.** An ABI-only change that incremental compilation fails to propagate would not be caught by the hash. The gate of record compiles clean, so it never depends on that.
- **v3 code never compiled.** SurfaceLookups.kt, the walk and report edits, PublicSurfaceIncrementalTest.kt, UnregisteredLookupsRegistrar.kt and the parse check have not been compiled, run, or passed through detekt and ast-grep. Their API calls are javap-verified; step 2 and step 5 are their first execution.
- **v3 inference: the test harness.** That the in-process Build Tools API runs the incremental compiler in the test's own class loader is inferred from the impl's service registration. So is the claim that the plugin loader's parent then holds UnregisteredLookupsRegistrar. Both rest on bytecode, not a run. So does the claim that `compilerArguments` returns the builder the operation keeps.
- **v3 inference: the alias route.** Route two expects a retargeted Bound to change Mid's serialized bound, which dirties User through its recorded Mid. If K2 instead keeps the alias in the bound, User's own record of Bound dirties it. Either way the registered arm should pass; neither has been run.
- **v3 inference: the dependencies resolve.** The test runtime has not resolved kotlin-build-tools-impl under Gradle. Every module its POMs name is pinned in verification-metadata.xml at the version they name. A newer kotlinx-coroutines on that classpath would win resolution, and that version would need its own pin.
- **v3 argued, not run: the owner invariant.** That the plugin never writes an undeclared owner is argued from the walk's code. The golden reports bear it out, and step 5's live run is its first check against the whole tree. A live refusal there names a plugin defect to fix, never an owner to exempt.
