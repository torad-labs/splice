# Anthropic Skill Best Practices

Consolidated from official Claude Code documentation, the Agent Skills blog post, and the plugin-dev skill-development guide.

## Skill Structure

```
skill-name/
├── SKILL.md (required, ~1500-2000 words)
├── references/   (loaded on demand, unlimited size)
├── examples/     (working code, loaded on demand)
├── scripts/      (executable utilities, may run without context load)
└── assets/       (output resources, not loaded into context)
```

## Progressive Disclosure (3 Levels)

| Level | Content | When Loaded | Size Target |
|-------|---------|-------------|-------------|
| 1. Metadata | name + description | Always in context | ~100 words |
| 2. SKILL.md body | Core instructions | When skill triggers | 1500-2000 words |
| 3. References | Detail, examples, API docs | When Claude needs them | Unlimited |

Rule: Information should live at the LOWEST level that still makes it discoverable. If Claude can find it via a pointer in SKILL.md, it belongs in references/.

## Frontmatter Fields

```yaml
---
name: skill-name              # lowercase, hyphens, max 64 chars
description: >-               # MOST IMPORTANT FIELD
  This skill should be used when the user asks to "phrase 1",
  "phrase 2", "phrase 3", or needs help with [domain].
user-invocable: true           # show in / menu (default true)
disable-model-invocation: false # prevent auto-trigger (default false)
allowed-tools: Read, Grep      # tools allowed without user approval
argument-hint: "[scope]"       # shown during autocomplete
context: fork                  # run in isolated subagent
agent: Explore                 # subagent type when context: fork
model: haiku                   # model override
---
```

### Description Rules

1. **Third person**: "This skill should be used when..." NOT "Use this skill when..."
2. **3+ trigger phrases**: Exact words users would type, in quotes
3. **Concrete over generic**: "create a hook" > "work with hooks"
4. **Non-overlapping with other skills**: Each skill's triggers must be distinct

### Invocation Control Matrix

| Setting | User invokes | Claude invokes | Context loaded |
|---------|-------------|----------------|----------------|
| (default) | Yes | Yes | Description always, body on trigger |
| disable-model-invocation: true | Yes | No | Nothing until user invokes |
| user-invocable: false | No | Yes | Description always, body on trigger |

## Writing Style

### Imperative/Infinitive Form

Write instructions as verb-first actions, not second person.

**Correct:** "Validate the JSON before parsing."
**Wrong:** "You should validate the JSON before parsing."

### No Second Person in Body

SKILL.md body should not use "you" or "your". The skill is instructions for Claude, written in objective instructional language.

**Correct:** "To accomplish X, do Y."
**Wrong:** "You should do X by doing Y."

## Content Organization

### What belongs in SKILL.md

- Core concepts and overview
- Essential procedures (the happy path)
- Quick reference tables
- Pointers to references/examples/scripts
- Key rejection boundaries (the most important constraints)

### What belongs in references/

- Detailed patterns and edge cases
- Comprehensive API documentation
- Advanced techniques
- Extended examples and walkthroughs
- Framework theory and background

### What belongs in scripts/

- Deterministic operations that shouldn't be rewritten each time
- Validation and testing utilities
- Automation helpers

## Common Mistakes

1. **Weak triggers**: Generic description → skill doesn't fire or fires wrong
2. **Bloated SKILL.md**: 5000+ words → wastes context on every trigger
3. **Second person**: "You should..." → inconsistent voice, less reliable following
4. **Missing resource pointers**: References exist but SKILL.md doesn't mention them
5. **Duplicate information**: Same content in SKILL.md AND references (pick one)
6. **Overlapping with other skills**: Two skills compete for the same trigger phrases

## String Substitutions

| Variable | Description |
|----------|-------------|
| `$ARGUMENTS` | All arguments passed to the skill |
| `$ARGUMENTS[N]` or `$N` | Nth argument (0-indexed) |
| `${CLAUDE_SESSION_ID}` | Current session ID |

## Dynamic Context

`!`command`` syntax runs shell commands before skill content is sent to Claude:

```yaml
## Current state
- Branch: !`git branch --show-current`
- Status: !`git status --short`
```

Command output replaces the placeholder. This is preprocessing — Claude sees only the result.
