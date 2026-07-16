---
name: api-checker
description: Use this skill to check your API routes for consistency problems.
  It analyzes route handlers and finds naming issues, missing middleware, and
  undocumented endpoints.
user-invocable: true
---

# API Checker

Check your API routes for consistency problems.

## Process

1. Read all route files in the `routes/` or `app/api/` directory.
2. For each route, check naming convention, middleware presence, and JSDoc.
3. Report findings in the output format below.

## Output Format

```
API Check Report
================

Found Issues:
- [route]: [issue description]
- [route]: [issue description]

Clean Routes:
- [route name] — passes all checks

Summary
-------
Total routes scanned: X
Issues found: Y
Routes passing all checks: Z

Severity levels:
  ERROR   — must fix before deploy
  WARNING — should fix soon
  INFO    — improvement opportunity
```

See [references/route-patterns.md](references/route-patterns.md) for naming
conventions and middleware requirements used during analysis.
