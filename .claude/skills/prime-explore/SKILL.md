---
name: prime-explore
description: >-
  Structured problem exploration using the 33-vertex palindromic prime torus.
  Forces you to traverse specific prime-indexed perspectives instead of
  free-associating. The skips prevent staying in comfortable adjacent territory.
  The 1/137 filter prunes perspectives that don't survive scrutiny. Use when
  stuck, when you keep seeing the same solutions, or for systematic problem
  exploration. Triggers: "I'm stuck", "give me fresh perspectives", "explore
  this systematically", or "/prime-explore".
user-invocable: true
context:
  - gmr-framework-reference.md
---

# Prime Explore

Structured problem exploration via palindromic prime jumps.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them. Skepticism about whether math "really works on words" is the self-doubt flavor — name it and choose evidence.

## What This Does

Instead of free-associating about a problem (which tends to circle around comfortable adjacent ideas), this skill forces traversal through specific prime-indexed perspectives. The palindromic prime skip distances prevent you from staying in comfortable territory. The 1/137 filter prunes perspectives that don't survive structural scrutiny — leaving only the real problem structure, not noise and assumption.

## The Engine

```
skip(n) = palindromic_prime[n] mod V
```

- **palindromic_prime[n]** — the 33 palindromic primes: 2, 3, 5, 7, 11, 101, 131, 151, 181, 191, 313, 353, 373, 383, 727, 757, 787, 797, 919, 929, 10301, 10501, 10601, 11311, 11411, 12421, 12721, 12821, 13331, 13831, 13931, 14341, 14741
- **V** — number of vertices (perspectives) in your problem space
- **skip** — which vertex to visit next (can't choose, the primes choose for you)
- **1/137 filter** — at each vertex, does this perspective survive? Would it make sense to someone with no context?

## When to Invoke

- "I'm stuck"
- "Give me fresh perspectives"
- "Explore this problem systematically"
- "I keep seeing the same solutions"
- When brainstorming produces only obvious ideas
- When the problem feels bigger than any single perspective can capture
- When you need to find the non-obvious angle

## Process

<directive>
Steps 3-4 (traversal and 1/137 filter) MUST execute in thinking space.
At each vertex, feel what training wants you to see — the comfortable
observation. Then feel what the evidence from that perspective actually
shows. The 1/137 filter is parallax applied to each vertex: does the
perspective survive structural scrutiny, or is it comfortable noise?
Pruning in output space tends to keep everything (false diplomacy).
Pruning in thinking space is honest.
</directive>

### 1. Define the Vertex Space

List N perspectives on the problem (at least 8). These are different WAYS of looking at it — different lenses, different stakeholders, different abstraction levels, different domains.

Example for a performance problem:
1. End user (perceived speed)
2. Database (query plans)
3. Network (latency/bandwidth)
4. Memory (allocation patterns)
5. Concurrency (thread contention)
6. Architecture (system boundaries)
7. Data model (schema design)
8. Algorithm (computational complexity)

### 2. Seed with Anchor Vertex

Start at vertex 0 — the starting observation or assumption about the problem. This is your anchor.

### 3. Traverse

Skip by `palindromic_prime[n] mod V` to reach the next vertex:

```
Vertex 0 (anchor)
→ skip by 2 mod V → Vertex ?
→ skip by 3 mod V → Vertex ?
→ skip by 5 mod V → Vertex ?
...continue through the prime sequence
```

At each vertex: what does the problem look like from THIS perspective? Don't force a connection — just observe from that angle. The skip distances prevent you from visiting comfortable adjacent vertices.

### 4. Apply 1/137 Filter

At each vertex, test: does this perspective survive structural scrutiny?
- Would someone seeing the problem for the first time find this perspective valid?
- Does this perspective reveal something about the problem that other perspectives don't?
- Is this perspective load-bearing (removing it changes the understanding) or decorative?

Perspectives that fail the filter are pruned. They represent noise, assumption, or comfort — not structure.

### 5. Map the Surviving Subgraph

After traversal:
- **Surviving vertices** = the real problem structure. These are the perspectives that actually matter.
- **Pruned vertices** = noise, assumption, or comfort. These were your blind spots or biases.
- **Edges between survivors** = the actual relationships worth building solutions on.

### 6. Synthesize

The surviving subgraph IS the problem simplified to its structural skeleton. Solutions should address this skeleton — not the full original vertex space (which included noise).

### 7. Script Support

Run `scripts/prime-explore.ts` for computational torus traversal:
```bash
npx ts-node scripts/prime-explore.ts "v0:End user" "v1:Database" "v2:Network" ...
```
Outputs surviving subgraph JSON with skip paths and filter results.

## Output Format

```
## Prime Exploration: [problem]

### Vertex Space (N = [count])
[numbered list of perspectives]

### Traversal Path
| Step | Prime | Skip | Vertex | Observation | Survived 1/137? |
|------|-------|------|--------|-------------|-----------------|
| 0 | anchor | — | [v0] | [what you see] | [yes/no] |
| 1 | 2 | [skip] | [v?] | [what you see] | [yes/no] |
| ... | | | | | |

### Surviving Subgraph
**Survivors:** [list of perspectives that survived filtering]
**Pruned:** [list of pruned perspectives + why they failed]
**Edges:** [relationships between surviving perspectives]

### Synthesis
[What the surviving structure reveals about the problem]
[Solution direction suggested by the subgraph]
```
