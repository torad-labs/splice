
# Bug Catcher

Build immunity against recurring bug classes.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them. Skepticism about whether math "really works on words" is the self-doubt flavor — name it and choose evidence.

## What This Does

Instead of patching individual bugs, this skill classifies bugs into pattern classes and builds immune memory against the entire class. Each time you catch a specific bug pattern and apply a structural fix (not just a patch), the immune memory for that class strengthens. Damping (λ) prevents tunnel vision on one bug class at the expense of others.

## The Engine

```
A_{n+1} = A_n + α·Δ·sgn(choice) − λ·A_n
```

- **A_n** — current immune memory strength for a bug class
- **α** — learning rate (how much each catch teaches)
- **Δ** — gap between obvious fix and structural fix
- **sgn(choice)** — +1 for structural fix (deposits immunity), -1 for patch (deposits nothing)
- **λ·A_n** — damping. Prevents over-fixation on one bug class.

## When to Invoke

- "We've seen this bug before"
- "This keeps happening"
- Recurring failures in the same area
- Post-mortem analysis
- "What's our most common bug type?"
- After fixing a bug — ask if the fix was structural or cosmetic
- During test suite design — what bug classes are unprotected?

## Process

<directive>
Step 4 (choosing patch vs structural fix) should include a thinking space
check: feel the pull toward the patch. Patches are comfortable — they make
the immediate problem go away. The structural fix is uncomfortable — it
requires more work and touches more code. The discomfort IS the signal
that the structural fix is the immune-memory-building choice.
</directive>

### 1. Classify the Bug

Identify the pattern class:
- **null-deref** — accessing properties on null/undefined
- **race-condition** — timing-dependent failures, concurrent access
- **injection** — SQL, XSS, command injection, template injection
- **type-mismatch** — wrong type passed, coercion errors
- **boundary** — off-by-one, array bounds, edge cases
- **state-leak** — stale state, retained references, event listeners not cleaned
- **encoding** — UTF-8, URL encoding, base64, JSON parse failures
- **auth-bypass** — missing auth checks, broken access control
- **resource-leak** — unclosed connections, file handles, memory
- **config-drift** — environment-specific failures, missing env vars

### 2. Check Immune History

Has this class been caught before in this codebase?
- How many times has this class appeared?
- What was the previous fix — structural or patch?
- Is A_n growing (catching more) or shrinking (letting them through)?

### 3. Measure Δ

What's the gap between the obvious fix and the structural fix?
- **Obvious fix** — the thing that makes this specific bug go away
- **Structural fix** — the thing that prevents this entire CLASS from recurring
- Large Δ = the structural fix is non-obvious (most valuable)

### 4. Choose: Patch or Structural Fix

- **Patch (sgn = -1):** Fixes this instance, deposits no immunity. Quick but you'll see this bug class again.
- **Structural fix (sgn = +1):** Fixes the class, deposits immunity. Takes more effort but builds resistance.

Examples of structural fixes per class:
| Class | Patch | Structural Fix |
|-------|-------|----------------|
| null-deref | Add `?.` at crash site | Make the type non-nullable. Use `Option<T>` or required fields. |
| race-condition | Add a setTimeout hack | Use proper locking, queue, or state machine |
| injection | Escape the specific input | Use parameterized queries everywhere, CSP headers |
| boundary | Fix the off-by-one | Use range types, iterator abstractions, property-based tests |
| state-leak | Clear the specific ref | Use cleanup hooks, RAII patterns, weak references |

### 5. Apply Damping

Check: are you over-focused on one bug class while ignoring others?
- Review the last 5-10 bugs. How many classes are represented?
- If one class dominates → damping is too low. Actively look for bugs in OTHER classes.
- If distribution is even → damping is healthy.

### 6. Record and Prescribe

Document for future immune memory:
- Bug class
- Location (file, function, module)
- Structural fix applied
- What test/type/pattern would prevent this CLASS from recurring

## Output Format

```
## Bug Catcher Analysis

**Bug class:** [null-deref / race-condition / etc.]
**Instance:** [specific bug description]
**A_n (immune history):** [how many times this class has been caught]

**Obvious fix (patch):** [what makes this instance go away]
**Structural fix:** [what prevents the entire class]
**Δ (gap):** [distance between patch and structural fix]
**Choice:** [patch / structural]

**Immunity deposit:** [what was learned, what test/type/pattern was added]
**Damping check:** [is focus balanced across bug classes?]
**Prescription:** [code/test that prevents this class from recurring]
```
