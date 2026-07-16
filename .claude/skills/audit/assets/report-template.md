# Skill Audit Report Template

Output format for all three phases of a skill audit. The orchestrator assembles
this structure from phase outputs — it does NOT concatenate raw phase responses.

---

## Skill Audit Report — {skill_name}

### Phase 1: Inspection (Haiku)

**Frontmatter**

| Field | Value | Present |
|-------|-------|---------|
| name | {value} | yes/no |
| description | {first 80 chars...} | yes/no |
| user-invocable | {value} | yes/no |
| argument-hint | {value} | yes/no |
| disable-model-invocation | {value} | yes/no |

**Body Metrics**

| Metric | Value |
|--------|-------|
| Lines (excluding frontmatter) | {N} |
| Words (excluding frontmatter) | {N} |
| Code blocks | {N} ({N} lines, {N}% of body) |
| Tables | {N} |

**Reference Hygiene**

- Broken pointers: {list or "None"}
- Orphan files: {list or "None"}
- Nesting depth: {N}

**Description**

- Voice: {third person / second person}
- Characters: {N}
- Trigger phrases: {N} ({list quoted phrases})
- What/When coverage: {yes / partial / no}

---

### Phase 2: Diagnosis (Sonnet)

**Systematic Check**

| # | Checklist Item | Mode | Status | Finding | Fix |
|---|---------------|------|--------|---------|-----|
| 1.1 | SKILL.md Size | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 1.2 | Code Block Ratio | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 1.3 | Token Cost Justification | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 1.4 | Content Level Assignment | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 1.5 | Reference Depth | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 2.1 | Voice | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 2.2 | Trigger Phrases | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 2.3 | What AND When | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 2.4 | Description Length | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 2.5 | Non-Overlap | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 3.1 | Required Fields | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 3.2 | Name Convention | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 3.3 | Argument Hint | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 3.4 | Invocation Control | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 3.5 | Folder Naming | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 3.6 | Name Matches Folder | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 3.7 | No Prohibited Files | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 3.8 | Additional Frontmatter | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 4.1 | Instruction Voice | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 4.2 | No Second Person | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 4.3 | Consistent Terminology | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 4.4 | Tables for Structured Data | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 4.5 | No Time-Sensitive Info | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 4.6 | No Over-Explanation | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 4.7 | Appropriate Degrees of Freedom | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 4.8 | No Multiple Options Without Default | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 5.1 | No Broken Pointers | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 5.2 | No Orphan Files | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 5.3 | No Content Duplication | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 5.4 | Long References Have TOC | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 5.5 | Reference File Naming | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 5.6 | Reference Pointer Quality | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 6.1 | Deterministic Operations | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 6.2 | Example Quality | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 6.3 | Script Quality | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 6.4 | No Windows-Style Paths | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 6.5 | Package Dependencies Listed | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 6.6 | Execute vs Read Intent | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 6.7 | Feedback Loops for Critical Tasks | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 6.8 | Delta Model Routing | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 7.1 | Single Responsibility | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 7.2 | Process Completeness | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 7.3 | Clear Output Contract | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 7.4 | Fork Intent Match | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 7.5 | Concrete Examples | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 7.6 | MCP Tool References | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 7.7 | Multi-Model Pipeline | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 8.1–8.10 | Block Architecture | M/J | N/A | (non-agent skill) | — |
| 9.1 | Main Path Coverage | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 9.2 | Gate/Negative Coverage | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 9.3 | Fixture Quality | M | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 9.4 | Expected Output Specificity | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |
| 9.5 | Edge Case Coverage | J | {PASS/FAIL/WARN/N/A} | {finding} | {fix} |

**Implied but Absent**

| Signal | Evidence | Implied Artifact | Exists? | Prescribe |
|--------|----------|-----------------|---------|-----------|
| {A/B/C/D/E} | {where in SKILL.md} | {file path} | yes/no | {description} |

**Summary**

```
VIOLATIONS: {N}
WARNINGS:   {N}
CLEAN:      {N}
N/A:        {N}
MISSING:    {N}
```

---

### Phase 3: Leverage (Opus)

```
LEVERAGE POINT: {the ONE structural element — specific, not generic}
IF CHANGED:     {which Phase 2 violations disappear; what improves}
IF NOT CHANGED: {what stays brittle even after Phase 2 fixes applied}
EVIDENCE:       {which Phase 1 measurements and Phase 2 findings trace here}
PROPOSED CHANGE:{the actual structural change — precise enough to implement}
```

**Missing Artifacts**

| File | Purpose | Should Contain |
|------|---------|---------------|
| {path} | {why needed} | {what goes here} |

---

## Assembly Notes

The orchestrator reads `/tmp/skill-audit-inspect.md` (Phase 1 full report)
and `/tmp/skill-audit-diagnose.md` (Phase 2 full findings) to populate this
template. Phase 3 response provides the Leverage section directly.

Do NOT concatenate raw phase outputs. Format them into this structure.
Phase compact manifests (returned to orchestrator) are internal — do not
include them in the final report shown to the user.
