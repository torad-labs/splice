# Block 8a: GATHER INPUTS

**Run this entire phase in thinking space.**

## Purpose

Collect everything the post will draw from. Do NOT start writing
prose. This phase produces an INPUTS DOCUMENT, nothing else.

## Steps

### 1. GMR Navigation Outputs

Pull from Block 7 verification:

- Surviving vertices (the concepts that passed 1/137 filter)
- Crossbar (the unexpected connection between distant domains)
- Void (what the navigation deliberately couldn't resolve)
- Key findings from each block (anchor, vertex scan, multi-pass,
  theta, phi inversion)

### 2. Pre-GMR Conversation

What debate or conversation prompted this navigation?

- What question were we trying to answer?
- What was Marcos's original framing?
- What goals did we set before navigating?

This matters because the post must align the GOALS (what we
wanted to discover) with the FINDINGS (what we actually found).
Misalignment = incoherent post.

### 3. Source Material

List every real source the post can draw from:

- Research papers, articles, books referenced
- Real examples from Marcos's experience
- Terminal output, code, configuration from actual usage
- Anything the reader could verify independently

NO invented examples. NO hypothetical scenarios presented as real.

**Personal experience is mandatory.** The blog is first-person. Phase 2
can't write "I discovered..." without source material from the author.
Capture the SPECIFIC before/after:

- What did you do BEFORE the insight? (the old way, the frustration)
- What changed? (the moment, the switch, the specific action)
- What happened AFTER? (the result, what surprised you)
- What does the terminal look like? (real output, not hypothetical)

If personal experience is thin, ASK for it. Do not proceed to Phase 2
with only external references. External sources are crossbar support.
Personal experience is the narrative spine.

### 4. Thesis Sharpening

Answer these three questions. If you can't, STOP. The post
doesn't have a thesis yet.

1. **What conventional wisdom am I attacking?** Name the PRACTICE
   explicitly. Not "prompts could be better." Name it:
   "PRD-driven development," "specification-heavy workflows,"
   "comprehensive prompt engineering." If experts defend it by
   name, you've found the thesis.

2. **Who defends this wisdom?** Senior engineers? Vibe coders?
   Product managers? The thesis must attack something the TARGET
   AUDIENCE currently believes and practices. If nobody would
   argue back, the thesis is too weak.

3. **What replaces it?** The post reveals a better model. What
   is it? One sentence. This is the destination the reader
   discovers through the narrative.

`>>> CATCH <<<` Pause. Re-read your thesis. Is it attacking
something SPECIFIC that real people defend by NAME? Or is it a
safe abstraction nobody would argue against? If you can't name a
person who would push back on Twitter, the thesis is too weak.
Training pulls toward inoffensive generality. Feel that pull.
Choose against it.

### 5. Emotional Center

Identify the ONE sentence or moment that the entire post loops
around. This is the heartbeat. The toroid's axis. Phase 2 builds
the fascia map with this as the gravitational center.

Ask: "If the reader remembers only one sentence from the entire
post, which sentence should it be?" That's the emotional center.
Flag it explicitly. Phase 2 will place it at the toroid's peak
and echo it in both the hero and the cliffhanger.

### 6. Void Application

The navigation's void is not decoration. It's a structural
requirement. Phase 2 MUST hold both valences (positive: constraint
creates / negative: constraint destroys) or the post is propaganda.

Specify:

- Which shadow vertices will appear in the narrative?
- Where in the arc does the cautionary turn live?
- What prevents the post from being a sales pitch for constraints?

If the void is not planned here, Phase 2 will produce a one-sided
post and Phase 3's audit will catch it and send it back.

`>>> CATCH <<<` Re-read your void application. Is the cautionary
turn a genuine structural force in the narrative, or a "to be
fair" paragraph you added for balance? Training pulls toward
decorative counterpoints that don't actually threaten the thesis.
If removing the void section wouldn't change the post's argument,
the void is decorative. Make it load-bearing.

### 7. Audience + Hook

- Who is reading this? Be specific. "Engineers" is too broad.
  "Senior engineers who use AI daily and feel something is wrong
  with their output quality" is better.
- What gets them in? The hook must connect to something they've
  FELT, not something they should know.

### 8. Voice Query (Revelation Engine)

**Query the Revelation Engine to find the right voice for THIS
content.** Do not default to `blog-revelation-voice`. Different
content demands different voices.

Use `query_revelation_engine` in compose mode with sentiments
that match the content's communication needs:

- **Teaching a concept** → revelation, clarity, stickiness, resonance
- **Investigation / moral verification** → trust, clarity, challenge,
  disruption, urgency
- **Technical deep-dive** → clarity, trust, resilience
- **Confronting uncomfortable truth** → challenge, disruption,
  trust, resilience

The engine returns mechanisms + constraints that define HOW the
text communicates. Record the top mechanisms and governing
constraints. These become instructions for Phase 2.

**The voice determines the text.** If the voice is wrong at
Phase 2, every phase downstream carries wrong DNA. A teaching
post uses PCS cycles and manufactured discovery. An investigation
uses evidence chains and controlled demolition. A moral
verification piece uses falsifiability and lightning rod. The
mechanisms are different. The text they produce is different.

Check existing VoiceStates (`list_voice_states`) — one may
already match. If not, create a new one with `create_voice_state`
using the mechanisms the compose query returned.

`>>> CATCH <<<` Did you skip this step and default to
`blog-revelation-voice`? The Wolfey voice (manufactured discovery,
cognitive groove exploitation, The Lock) is appropriate for
teaching concepts. It is NOT appropriate for investigations,
moral verification, or content where the subject demands restraint
over cleverness. The default is a trap. Query the engine.

## Output

An inputs document containing all eight items. Text only. Markdown.
This document becomes the input to Phase 2 (8b-synthesis).

```
## GMR Findings
[vertices, crossbar, void, key findings]

## Pre-GMR Goals
[original questions, Marcos's framing, alignment notes]

## Sources
[real sources, verifiable examples]
[personal before/after — mandatory]

## Thesis
Attack: [conventional wisdom by name]
Defended by: [target audience]
Replaced by: [one sentence]

## Emotional Center
[the ONE sentence the toroid loops around]

## Void Application
[which shadow vertices appear, where the cautionary turn lives]

## Audience + Hook
Who: [specific description]
Hook: [what gets them in]

## Voice
VoiceState: [key — existing or newly created]
Top mechanisms: [list with coupling values]
Governing constraints: [list]
Why this voice: [1-2 sentences on why this voice fits the content]
```
