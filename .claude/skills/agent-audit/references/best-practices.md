# Agent Best Practices Reference

Compiled from Anthropic's engineering blog, API documentation, and Claude Code
documentation (February 2026). Used by Phase 2 (Sonnet) to check agent
architecture against known patterns.

## 1. Subagent Task Specification (Anthropic Multi-Agent Research)

Every subagent requires four components:
1. **Explicit objective** — what to accomplish
2. **Output format** — how to structure results
3. **Tool/source guidance** — which tools to use and where to look
4. **Clear task boundaries** — what NOT to do, where the scope ends

**Anti-pattern**: Vague instructions like "research this area" cause subagents
to duplicate work. One agent explores 2021 data while two others investigate
identical 2025 supply chains.

**Scaling heuristic**:
- Simple fact-finding: 1 agent, 3-10 tool calls
- Direct comparisons: 2-4 subagents, 10-15 calls each
- Complex multi-aspect work: 10+ subagents with divided responsibilities

## 2. Context Forwarding (Anthropic Multi-Agent Research)

- Save research plan to external memory before context approaches 200K tokens
- Agents summarize completed work phases — don't rely on conversation history
- Subagents output to external systems (files, DB) with lightweight references
  back to coordinator — prevents token overhead from copying large outputs
- Spawn fresh subagents with clean contexts; maintain continuity through
  structured handoffs, not conversation history

**Anti-pattern**: Forwarding full subagent output through conversation history.
This causes context bloat in the orchestrator. Forward summaries, not transcripts.

## 3. Prompt Design for Agents (Claude 4.6 Best Practices)

### Heuristics Over Rules
Encode human strategies — decomposing questions, evaluating quality, adjusting
approach — rather than rigid step sequences. "Prefer general instructions over
prescriptive steps. A prompt like 'think thoroughly' often produces better
reasoning than a hand-written step-by-step plan."

### Dial Back Aggressive Language
Claude 4.6 overtriggers on instructions written for previous models. Where
you might have said "CRITICAL: You MUST use this tool when...", use "Use this
tool when...". The model is more responsive to system prompts now.

**Audit check**: Flag directives using CRITICAL, MUST, ALWAYS, NEVER, IMPORTANT
when simpler language would suffice. These may cause overtriggering.

### Think After Tool Results
Use interleaved thinking to reflect on results before proceeding:
"After receiving tool results, carefully reflect on their quality and determine
optimal next steps before proceeding."

### Search Strategy
"Agents often default to overly long, specific queries that return few results."
Prompt for short, broad queries first, then narrow progressively.

### Tool Descriptions
Bad tool descriptions derail agents completely. Each tool needs distinct purpose
and clear description. Examine all available tools first, match usage to intent,
prefer specialized tools over generic ones.

## 4. Advanced Tool Use Features (Often Underutilized)

### Tool Search Tool
Dynamically discovers tools on-demand instead of loading all definitions upfront.
- Mark tools with `defer_loading: true`
- 85% reduction in token consumption for 10+ tool libraries
- Accuracy: 49% → 74% (Opus 4), 79.5% → 88.1% (Opus 4.5)

**Audit check**: If an agent exposes 10+ tools to a single block, it should
use tool search. Otherwise tool definitions consume context.

### Programmatic Tool Calling (PTC)
Claude writes Python code that orchestrates multiple tool calls in a sandbox.
Results stay in execution context — only the final output returns to Claude.
- Token savings: 37% reduction on complex tasks
- Eliminates 19+ inference passes in 20-tool workflows

**Audit check**: If a block makes 3+ dependent tool calls sequentially, PTC
may be more efficient.

### Tool Use Examples
Teaches correct tool usage beyond what JSON Schema can express.
- 1-5 realistic examples per tool
- Show actual data (real names, plausible values), not "string" or "value"
- Focus on ambiguities: date formats, ID conventions, nested patterns
- Accuracy improved from 72% → 90%

**Audit check**: Do tool schemas have `input_examples`? If the tool has
complex nested structures or domain conventions, examples are high-value.

## 5. Subagent Architecture (Claude Code Documentation)

### Key Constraints
- Subagents cannot spawn other subagents (single nesting depth)
- Each subagent gets its own system prompt + basic environment info, NOT the
  full parent system prompt
- Skills can be preloaded via the `skills` field in frontmatter
- Persistent memory survives across sessions (user, project, or local scope)

### Permission Modes
- `default`: standard prompts
- `acceptEdits`: auto-accept file edits
- `dontAsk`: auto-deny prompts (explicitly allowed tools still work)
- `bypassPermissions`: skip all checks (use with caution)
- `plan`: read-only exploration

### Hooks for Validation
Use `PreToolUse` hooks for conditional tool validation — e.g., allowing Bash
but blocking SQL write operations. More granular than the `tools` allowlist.

### When to Use Subagents vs Main Conversation
Use **subagents** when:
- Task produces verbose output you don't need in main context
- You want to enforce specific tool restrictions
- Work is self-contained and can return a summary

Use **main conversation** when:
- Task needs frequent back-and-forth
- Multiple phases share significant context
- Latency matters (subagents start fresh and gather context)

## 6. Model Assignment (Claude 4.6 Documentation)

### Cognitive Shape Matching
- **Haiku**: Classification, extraction, validation, simple tool calls.
  Fast, cheap. Don't give it synthesis tasks.
- **Sonnet**: Structural design, code generation, moderate analysis.
  Good balance of speed and capability. Best for tool-heavy workflows.
- **Opus**: Creative generation, deep reasoning, leverage identification,
  long-horizon planning. Highest quality ceiling but expensive.

**Audit check**: Is each block assigned to the right model for its cognitive
demands? A block doing creative prose on Haiku will underperform. A block doing
simple classification on Opus wastes tokens.

### Adaptive Thinking
Claude 4.6 uses adaptive thinking (`thinking: {type: "adaptive"}`) by default.
The model decides when and how much to think. Higher `effort` settings elicit
more thinking.

**Audit check**: Are agent blocks using appropriate effort levels? Extraction
blocks don't need `high` effort. Creative and planning blocks benefit from it.

## 7. Evaluation Patterns (Anthropic Multi-Agent Research)

### Start Small
Begin with ~20 test queries representing real usage. Early development shows
dramatic effect sizes (30% → 80% with prompt tweaks).

### LLM-as-Judge Rubric
Single LLM call evaluating:
- Factual accuracy (claims match sources)
- Completeness (all requested aspects covered)
- Source quality (primary over secondary)
- Tool efficiency (appropriate tool count)

### Outcome-Based Assessment
Evaluate whether agents achieved correct final states rather than validating
specific steps. Agents may find alternative valid paths.

### Human Edge Cases
Automated evals miss: hallucinations on unusual queries, system failures,
and subtle biases. Human testers discovered agents preferring SEO-optimized
content farms over authoritative sources.

## 8. Token Economics (Anthropic Multi-Agent Research)

- Single agents: 4× more tokens than chat
- Multi-agent systems: 15× more tokens than chat
- Token usage explains 80% of performance variance
- Model choice and tool call count explain remaining 15%

**Audit check**: Is the multi-agent approach justified for the task's value?
Not suitable for tasks requiring shared context across all agents or many
inter-agent dependencies.

## 9. Resilience Patterns (Anthropic Engineering)

### Graceful Degradation
Don't restart from beginning on failure. Let agents know tools failed and
allow them to adapt. Combine AI adaptability with deterministic safeguards
(retry logic, checkpoints).

### Error Cascade Awareness
"Minor changes cascade into large behavioral changes." One failed step causes
agents to explore entirely different trajectories. Add checkpoints.

### Long-Running Agent Harness
- Initializer agent (first session): sets up environment, creates progress
  files, initial git commit
- Coding agent (subsequent sessions): reads progress, picks next task, makes
  incremental progress, leaves structured artifacts
- Use a DIFFERENT prompt for the first context window vs subsequent ones
- Structured state: JSON for test results/task status, freeform for progress
- Git for state tracking across sessions

## 10. Parallelization (Anthropic Multi-Agent Research)

- Lead agent spins up 3-5 subagents in parallel, not serially
- Each subagent uses 3+ tools in parallel
- Result: up to 90% reduction in research time for complex queries

### Current Limitation
Lead agents wait for all subagents to complete. Cannot steer mid-task, cannot
coordinate between subagents. Entire system blocked by slowest subagent.

**Audit check**: Are there pipeline stages that could run in parallel but are
currently sequential? Are there parallel blocks that should be sequential
because they depend on shared state?

## 11. Prompt Anti-Patterns to Detect

| Anti-Pattern | Why It's Bad | Fix |
|---|---|---|
| "CRITICAL: YOU MUST" language | Overtriggers on Claude 4.6 | Use natural instructions |
| Overly specific queries | Returns few results, narrows too early | Start broad, narrow progressively |
| Vague subagent instructions | Causes duplicated work | Explicit objective, format, boundaries |
| Forwarding full output in conversation | Context bloat | Use file artifacts, return summaries |
| Prescriptive step sequences | Constrains model capability | Encode heuristics, not rigid steps |
| No tool use examples | 72% accuracy vs 90% with examples | Add 1-5 realistic examples |
| Creative block on wrong model | Haiku can't do creative prose | Match model to cognitive demands |
| Testing only via AI eval | Misses hallucinations and edge cases | Add human testing pass |
| No checkpoint/state management | Error cascades, lost progress | Save state before heavy operations |
| Tools listed without descriptions | Model can't select the right tool | Each tool needs distinct purpose |
