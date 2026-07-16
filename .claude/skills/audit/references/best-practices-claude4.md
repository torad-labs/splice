# Claude 4 Best Practices for Skills

Consolidated from Anthropic official documentation (context7, platform.claude.com, migration guide, prompting best practices) for Sonnet 4.6 and Opus 4.6.

## What Changed in Claude 4

Claude 4 models (Sonnet 4.6, Opus 4.6) have a **more direct and concise communication style** than Claude 3.x. They follow instructions more literally and require more explicit direction.

> *"Claude 4+ models exhibit a more direct and concise communication style, requiring more explicit direction in prompts."*
> — Anthropic migration guide

**Practical consequence:** Instructions that worked with Claude 3.x by implication, soft suggestion, or vague phrasing will be ignored or weakly executed by Claude 4. The model does not infer intent — it executes what is explicitly stated.

---

## 1. Imperative Phrasing — Required, Not Preferred

Claude 4 treats **descriptions as context** and **commands as directives**. A skill body written as descriptions of what the skill does will produce weak, inconsistent behavior.

### Rule

Every instruction must be a verb-first command. Not a description of what Claude might do.

### Examples

| Wrong (description) | Correct (imperative) |
|---------------------|----------------------|
| "This phase helps identify structural issues." | "Identify all structural issues. Report each with a severity label." |
| "The skill explores the design space before converging." | "Explore at least three distinct directions before recommending one." |
| "You might want to check for broken links." | "Check all links in SKILL.md. Report broken ones before proceeding." |
| "Consider using the validation script." | "Run `scripts/validate.sh` before reporting results." |
| "Feel free to ask for clarification." | "Start immediately. Ask questions only after producing the first output." |

### Anti-patterns to flag

- Phrases beginning with: "helps", "allows", "enables", "can", "may", "might", "could", "considers", "explores"
- "You might want to..." / "You could..." / "Feel free to..."
- Descriptions of capability instead of directives to act

---

## 2. XML Tags for Structural Separation

When a skill body contains multiple distinct sections (instructions, constraints, output format, examples), wrap each in named XML tags. Claude 4 treats the body as one undifferentiated block without them — sections that appear later are deprioritized.

### Recommended structure

```xml
<instructions>
[Core action — what to do, step by step]
</instructions>

<constraints>
[Non-negotiables — what must not happen, hard stops]
</constraints>

<output_format>
[Exact format of what to produce]
</output_format>

<examples>
[Concrete input/output pairs]
</examples>
```

### When XML tags are needed

Use XML separation when:
- The skill body is over 300 words
- There are 3+ distinct instruction types (what to do, what not to do, format)
- The skill has a pipeline with multiple phases
- There are constraints that must hold regardless of other instructions

### Tag name convention

No universally mandated names — use names that logically describe the content. Common:
- `<instructions>`, `<steps>`, `<process>`
- `<constraints>`, `<rules>`, `<non-negotiables>`
- `<output_format>`, `<format>`, `<structure>`
- `<examples>`, `<example>`, `<input>`, `<output>`
- `<context>`, `<background>`, `<when-to-use>`

Do NOT use XML tags in the description frontmatter field — they can interfere with system prompt injection.

---

## 3. Instruction Ordering — Critical First

Claude 4 is sensitive to ordering. Instructions earlier in the body carry more weight. The current common pattern (context first, instructions after) inverts this.

### Correct order

1. **Core action** — the imperative of what to do (first paragraph, above everything)
2. **Constraints and hard stops** — what must not happen
3. **Output format** — what to produce and how
4. **Background and context** — why this works, domain knowledge
5. **Examples** — concrete illustration
6. **Reference pointers** — links to detailed files

### Anti-pattern to flag

Skills where the first 200 words are context/background and the actual instruction appears after a heading halfway down the body. Claude 4 may have already formed its approach before reaching the instruction.

### Reference sections belong after `</instructions>`

Pointers to files (links to references/, phases/, assets/, scripts/) are not instructions — they are navigation aids. Place them **after** the closing `</instructions>` tag, not inside it. Authoring trap: it feels natural to add file pointers at the end of a step ("For the full pattern, see references/foo.md") — but if that step is inside `<instructions>`, the pointer pollutes the instruction block. Extract all reference lists to a section below `</instructions>`.

**Correct:**
```
<instructions>
...step content...
</instructions>

## References
- [references/foo.md](references/foo.md) — description
```

**Wrong:**
```
<instructions>
...step content...
For the full pattern, see [references/foo.md](references/foo.md).
</instructions>
```

---

## 4. Positive Framing — Tell What To Do, Not What Not To Do

Claude 4 responds better to "do X" than "don't do Y." Negative instructions are still processed, but they are less reliable as the primary directive.

### Rule

Frame the desired behavior positively. Use negatives only to sharpen the boundary.

### Examples

| Negative (weaker) | Positive (stronger) |
|-------------------|---------------------|
| "Don't ask for clarification before starting." | "Start immediately with available information." |
| "Don't summarize — produce the actual output." | "Produce the full output. Summaries are not acceptable." |
| "Don't skip the validation step." | "Run validation. Do not report results until validation passes." |
| "Don't add features beyond what was asked." | "Implement exactly what was requested. Flag anything that would expand scope." |

---

## 5. Theory of Mind — Pair Imperatives With Rationale

Claude 4 commits more reliably to instructions when the rationale is stated. This is not softening the instruction — it is giving the model the context to apply the instruction correctly in edge cases.

### Pattern

```
[Imperative]. ([One-sentence rationale — why this matters or what goes wrong without it].)
```

### Examples

```
Run all three phases before reporting findings. (Haiku misses what Opus catches — the pipeline exists because sequential models see different gaps.)

Check all internal links before starting analysis. (Broken pointers create false-positive structural findings that dominate the report.)

Read the script output before interpreting results. (The script's measurements are ground truth — LLM re-derivation produces inconsistent numbers.)
```

### When to include rationale

- Instructions that feel arbitrary without context
- Instructions that conflict with the model's defaults (e.g., "don't ask questions")
- Instructions involving tool use or script execution
- Any instruction that a reasonable Claude instance might skip as "optional"

---

## 6. Description Field as Routing Signal

The description field is the **only content Claude reads when deciding whether to trigger a skill**. The body is only loaded after trigger. This means the description must function as a routing signal, not a marketing summary.

### Structure of an effective description

```
[What the skill does — verb-first, specific] when [trigger condition 1], [trigger condition 2], or [trigger condition 3]. [What it does NOT do — disambiguation against adjacent skills.]
```

### Routing disambiguation (critical for adjacent skills)

When two skills compete for similar trigger phrases, the description must include negative disambiguation:

```
Use when auditing existing skills — NOT when building new ones (use build-skill for that).
```

### Claude 4 routing behavior

Claude 4 matches descriptions more literally. Vague descriptions that relied on Claude 3's fuzzy matching will route incorrectly. Each trigger phrase should be:
- **Specific** ("scan the codebase for coupling issues" not "code quality")
- **Phrased as users actually type** ("my dependencies are outdated" not "dependency management")
- **Distinct from adjacent skills** — no phrase should fire multiple skills

### Description field constraints (from Anthropic spec)

- Maximum 1024 characters
- No XML tags
- Third person: "This skill should be used when..." (not imperative)
- 3+ quoted trigger phrases minimum

---

## 7. Instruction Length Calibration

Claude 4's literalism means both under-specification and over-specification create problems.

### Under-specified instruction
```
"Analyze the codebase."
```
Claude 4 will pick its own scope, depth, and format. Results are inconsistent.

### Over-specified instruction
```
"Look at every file, check every line, classify every function into one of the following 17 categories..."
```
Claude 4 may follow this literally and produce mechanical output, or time out on large inputs.

### Calibrated instruction
```
"Scan the codebase. Identify the top 5 complexity hotspots by: cyclomatic complexity, coupling count, and file size. Report each as: [file] — [metric] — [one-line diagnosis]."
```

The rule: **specify what you want, not how to compute it.** Give the output contract, not the algorithm.

---

## 8. Parallel Tool Use (Claude 4 Native)

Claude Opus 4.6 and Sonnet 4.6 handle parallel tool use natively without complex prompting. Skills that previously needed explicit "call these tools in parallel" instructions can simplify:

**Old pattern (unnecessary for Claude 4):**
```
"First call tool A, then while waiting call tool B simultaneously, then..."
```

**Claude 4 pattern:**
```
"Read the SKILL.md and the references directory. Report findings."
```

Claude 4 will parallelize the reads automatically. Over-specifying tool call order in Claude 4 can actually hurt — it constrains parallel execution.

---

## 9. Checklist: Claude 4 Compliance for a SKILL.md

Run against any SKILL.md body:

| Check | Pass condition | Common failure |
|-------|---------------|----------------|
| Imperative phrasing | >90% of instructions are verb-first commands | "This skill helps...", "You might want to..." |
| No soft qualifiers | No "may", "might", "could", "consider", "feel free" | "Feel free to ask for clarification" |
| Critical instructions first | Core action appears in first 100 words | Long context section before any imperative |
| XML separation | Distinct sections wrapped in named tags (if body >300 words) | One flat block for instructions + constraints + format |
| Positive framing | Desired behavior stated positively | "Don't skip validation" without positive equivalent |
| Rationale attached | Non-obvious instructions have one-sentence rationale | Bare imperative that feels arbitrary |
| Description specificity | 3+ specific trigger phrases, distinct from adjacent skills | Generic phrases, no disambiguation |
| No description XML | No XML tags in frontmatter description | `<tags>` in description field |
| Parallel tool use | No manual tool sequencing instructions | "First call X, then call Y" when both are reads |

---

## Sources

- [Anthropic Migration Guide — Claude 4](https://platform.claude.com/docs/en/about-claude/models/migration-guide)
- [Anthropic Prompting Best Practices](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/claude-prompting-best-practices)
- [XML Tags in Prompts](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/use-xml-tags)
- [Skill Creator SKILL.md — Anthropic](https://github.com/anthropics/skills/blob/main/skills/skill-creator/SKILL.md)
- [Tool Use System Prompt Structure](https://platform.claude.com/docs/en/agents-and-tools/tool-use/implement-tool-use)
- [Claude 4.5 What's New — Claude 4 Best Practices](https://platform.claude.com/docs/en/about-claude/models/whats-new-claude-4-5)
