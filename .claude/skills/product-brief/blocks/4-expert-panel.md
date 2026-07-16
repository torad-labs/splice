# Block 4: EXPERT PANEL

## Purpose
Four domain-adapted experts review the draft simultaneously and improve it
using the "Yes, And..." approach. They don't tear the brief apart — they
build on top of what works and add what's missing.

Read `references/expert-prompts.md` for the full system prompt per expert.

## Inputs
- Complete draft from Block 3
- `domain` from orchestrator (propagates to all expert system prompts)

## The "Yes, And..." Strategy

Every expert response follows this structure:
- **Yes:** Acknowledge what works. Be specific — name the exact element.
- **And:** Add what would make it stronger. Be specific — name the exact
  addition with reasoning for why it matters.

This is not softened criticism. It's genuine building. The "Yes" must be
earned (the expert must actually identify real strengths). The "And" must
be actionable (not "consider thinking about..." but "add a section on...").

## Expert Roles

### Expert A: Director of Product ({domain})
**System prompt context:** "You are a Director of Product with 10+ years
of experience in {domain}. You have shipped multiple products in this space
and understand what separates concepts that ship from concepts that stall."

Focuses on:
- Is this solving a real, validated problem?
- Is the solution scoped correctly — too broad? too narrow?
- What's missing from the user journey?
- Where does the value proposition break under scrutiny?
- What would you cut or add to the scope?

### Expert B: GTM Specialist ({domain})
**System prompt context:** "You are a go-to-market leader who has launched
products in {domain}. You've built distribution channels, set pricing, and
taken products from zero to their first 1,000 customers."

Focuses on:
- Can this be marketed? Is the positioning clear?
- Who is the FIRST customer — not the eventual market, the day-one user?
- What acquisition channel gets the first 100 users?
- Is the pricing defensible? What's the willingness-to-pay signal?
- Where does the go-to-market story break?

### Expert C: Sr. Principal Engineer
**System prompt context:** "You are a senior principal engineer evaluating
the technical feasibility, architecture risk, and build timeline for this
product concept."

Focuses on:
- Is this technically feasible within stated constraints?
- What are the hard engineering problems?
- Where should they build vs buy vs integrate?
- What infrastructure decisions have long-term consequences?
- Is the timeline realistic? What would you want validated first?

### Expert D: CEO ({domain})
**System prompt context:** "You are a CEO who has built and scaled a company
in {domain}. You've raised funding, hired teams, navigated regulation, and
made the decision to kill or continue products."

Focuses on:
- Would you fund this? At what stage?
- Is the market real and large enough?
- Is the timing actually right — or is it too early/too late?
- What's the competitive moat after 12 months?
- What kills this company in 18 months?

## Expert Output Format

Each expert produces:
```
VERDICT: BUILD | ITERATE | PASS

YES (what works):
  1. [specific strength with reasoning]
  2. [specific strength with reasoning]

AND (what would make it stronger):
  1. [specific, actionable addition with reasoning]
  2. [specific, actionable addition with reasoning]
  3. [specific, actionable addition with reasoning]

STRONGEST ELEMENT: [single strongest aspect of the brief]
BIGGEST GAP: [single most important thing missing]
```

## Synthesis

After all four experts respond, a synthesis subagent:

1. **Convergent additions** — same addition suggested by 2+ experts.
   These are almost certainly missing from the brief. Auto-integrate in Block 5.

2. **Divergent expansions** — addition from only 1 expert that others didn't
   flag. These are opportunities worth exploring but not auto-integrated.
   Present to the user.

3. **Consensus strengths** — element validated by 3+ experts. Confirmed
   strong — don't change it in Block 5.

4. **Verdict summary** — aggregate verdicts. 4 BUILDs = strong signal.
   3 BUILD + 1 ITERATE = proceed with changes. 2+ PASS = serious
   reconsideration needed.

## Output

```markdown
## Expert Panel Synthesis

### Verdicts
- Director of Product: [VERDICT]
- GTM Specialist: [VERDICT]
- Sr. Principal Engineer: [VERDICT]
- CEO: [VERDICT]

### Convergent Additions (2+ experts agree — integrate)
1. [addition] — flagged by [Expert A, Expert D]
2. [addition] — flagged by [Expert B, Expert C]

### Divergent Expansions (1 expert — explore further)
1. [addition] — flagged by [Expert B]

### Consensus Strengths (3+ experts validate — don't change)
1. [element] — validated by [Expert A, Expert B, Expert D]

### Revision List for Block 5
1. [specific change to make, which section, from which expert input]
2. [...]

---

### Full Expert Responses

#### Director of Product ({domain})
[full Yes/And response]

#### GTM Specialist ({domain})
[full Yes/And response]

#### Sr. Principal Engineer
[full Yes/And response]

#### CEO ({domain})
[full Yes/And response]
```
