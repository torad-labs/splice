# Torad Kotlin Rules

Canonical Kotlin rules enforcing Torad engineering tenets. **65 ast-grep rules** + detekt config + konsist architecture tests.

## Quick Install

```bash
# Via torad CLI (recommended)
torad add kotlin-rules

# Manual: copy to project
cp -r .rules/kotlin/* your-project/
```

## Components

### 1. ast-grep Rules (`.rules/kotlin/ast-grep/`)

Structural pattern rules. Run with:

```bash
# Scan project
ast-grep scan --config .rules/kotlin/ast-grep/sgconfig.yml

# In CI
ast-grep scan --config .rules/kotlin/ast-grep/sgconfig.yml --json
```

**65 Rules:**

| Rule | Category |
|------|----------|
| **Architecture & Structure** | |
| `no-extension-functions` | Use explicit receiver parameter |
| `no-sealed-interface` | Prefer sealed class over sealed interface |
| `no-loose-function` | Behavior belongs on a type |
| `no-companion-objects` | Use top-level declarations |
| `no-secondary-constructor` | Primary constructor + factories |
| `no-invented-layer` | No Controller/Presenter/Contract |
| `no-lambda-seam` | Use fun interface, not raw function types |
| `sealed-class-no-body-properties` | Shared properties in constructor |
| **Flow & Concurrency** | |
| `flow-over-suspend` | Flow is composable, suspend is single-shot |
| `data-layer-flow-only` | Repository/DataSource returns Flow |
| `usecases-return-flow` | UseCases return Flow not suspend |
| `no-logic-in-collect` | Thin collect terminals |
| `no-global-scope` | Use structured concurrency |
| `no-coroutine-scope-factory` | Use viewModelScope/lifecycleScope |
| `no-runblocking-in-production` | Use suspend functions properly |
| `no-delay-in-production` | Use Flow operators instead |
| `main-no-hardcoded-dispatchers` | Inject CoroutineDispatcher |
| **State & UI** | |
| `state-must-be-sealed` | State via sealed class hierarchy |
| `immutable-ui-state` | No var in UiState |
| `no-logic-in-ui` | UI renders, doesn't compute |
| `no-logic-in-uistate` | UiState is pure data |
| `no-logic-in-viewmodel` | ViewModel composes UseCases |
| `no-logic-in-data-class` | Data classes carry data only |
| `converters-must-be-usecases` | Converters are injectable |
| `sealed-over-stringly` | Use sealed types, not string dispatch |
| `uistate-no-nullable-string-defaults` | Use empty string, not null |
| **Compose** | |
| `composable-returns-unit` | Composables emit, don't return |
| `mutablestateof-needs-remember` | remember { mutableStateOf() } |
| `no-mutablestateof-in-viewmodel` | ViewModel uses StateFlow |
| `lazy-items-need-key` | items() needs key parameter |
| `collect-with-lifecycle` | Use collectAsStateWithLifecycle |
| `no-compose-in-core` | Core modules are pure Kotlin |
| **Safety & Null Handling** | |
| `no-unsafe-bang-bang` | No !! — use ?:, requireNotNull |
| `no-mutable-var` | Prefer val |
| `no-else-in-sealed-when` | Exhaustive when |
| `no-name-shadowing` | No lambda parameter shadowing |
| `no-empty-catch` | Handle or log exceptions |
| `no-unsafe-cast` | Use as? safe cast |
| `no-any-parameters` | Use specific types or generics |
| `no-lateinit` | Use constructor injection or lazy |
| `use-or-empty` | Use .orEmpty() not ?: "" |
| `prefer-immutable-collections` | Don't return mutable collections |
| **Testing** | |
| `tests-no-runblocking` | Use runTest, not runBlocking |
| `tests-no-thread-sleep` | Use virtual time |
| `tests-no-hardcoded-dispatchers` | Inject TestDispatcher |
| `tests-no-mockk-static` | Use hand-rolled fakes |
| `tests-flow-via-turbine` | Use Turbine for Flow testing |
| **Injection Discipline** | |
| `vm-injection-discipline` | ViewModel injects UseCases only |
| `usecase-injection-discipline` | UseCase depends downward |
| `repository-injection-discipline` | Repository injects DataSources |
| `no-ui-projection-in-di` | DI graphs don't hold UiState |
| **Seams & Boundaries** | |
| `no-network-outside-seam` | HTTP clients in provider only |
| `tools-stateless` | Tool files are stateless |
| `tools-no-private-helpers` | Tools delegate to UseCases |
| `embeddings-via-provider` | Embedding clients in provider |
| `sql-only-in-persistence` | SQL in persistence layer only |
| **Production Safety** | |
| `no-println-in-production` | Use logging framework |
| `agent-no-println` | Agent code uses middleware |
| `no-todo-throws` | No TODO() in production |
| `no-raw-thread` | Use coroutines |
| `no-system-exit` | Throw exceptions for shutdown |
| `no-hardcoded-secrets` | Use env vars or vaults |
| `no-reflection-in-production` | Use interfaces/generics |
| `no-object-singleton-state` | No mutable state in objects |
| `prompts-no-buildstring` | Prompts in markdown files |

### 2. detekt Configuration (`detekt.yml`)

Quality rules including complexity, naming, style, and coroutines. Copy to project root:

```bash
cp .rules/kotlin/detekt.yml detekt.yml
```

Add to `build.gradle.kts`:

```kotlin
plugins {
    id("io.gitlab.arturbosch.detekt") version "1.23.+"
}

detekt {
    config.setFrom("detekt.yml")
    buildUponDefaultConfig = true
}

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.+")
}
```

### 3. Konsist Tests (`konsist/`)

Architecture tests with 20+ checks. Copy to `src/test/kotlin/`:

```bash
cp -r .rules/kotlin/konsist/* src/test/kotlin/com/yourproject/konsist/
```

Add to `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation("com.lemonappdev:konsist:0.17.0")
}
```

**Konsist checks include:**
- Layer dependency validation (domain → data → presentation → ui)
- UseCase structure (single operator invoke)
- UseCase/Repository/ViewModel injection discipline
- Sealed class structure (nested variants)
- Immutability (no var in UiState, no mutable collections)
- No extension functions in main sources
- Converter structure (UseCase classes, not functions)
- Android import isolation (no Android in domain)
- Repository returns Flow, not suspend

## CI Integration

### GitHub Actions

```yaml
- name: Run ast-grep rules
  run: |
    npm install -g @ast-grep/cli
    ast-grep scan --config .rules/kotlin/ast-grep/sgconfig.yml

- name: Run detekt
  run: ./gradlew detekt

- name: Run konsist tests
  run: ./gradlew test --tests "*KonsistTest*"
```

### Gradle Task

```kotlin
tasks.register<Exec>("astGrepCheck") {
    commandLine("ast-grep", "scan", "--config", ".rules/kotlin/ast-grep/sgconfig.yml")
}

tasks.named("check") {
    dependsOn("astGrepCheck", "detekt")
}
```

## Tenets Reference

These rules encode Torad engineering tenets:

1. **Single source of truth via sealed classes** — nested data class variants
2. **Separate UI from domain** — always, even when identical
3. **Extract UI logic to converters** — UI renders, doesn't compute
4. **Flow over suspend** — composability wins
5. **Exhaustive representations** — sealed everywhere
6. **UseCase composition** — small, injectable, composable
7. **Immutability** — val, copy(), no mutation
8. **Injection discipline** — layers depend downward only
9. **Seams** — network/tools in dedicated modules

## Customization

### Disable a rule

Remove the rule file from `rules/`, or in CI add `--exclude`:

```bash
ast-grep scan --config sgconfig.yml --exclude 'no-companion-objects'
```

### Adjust detekt thresholds

Edit `detekt.yml`:

```yaml
complexity:
  LongMethod:
    threshold: 80  # Increase from 50
```

### Skip konsist test

```kotlin
@Disabled("Temporary: migrating to new package structure")
@Test
fun `architecture layers have correct dependencies`() { ... }
```
