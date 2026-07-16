# JSON Schemas

Data structures used across the skill eval loop.

---

## evals.json

Stores test cases for a skill.

```json
{
  "skill_name": "example-skill",
  "evals": [
    {
      "id": 1,
      "prompt": "User's task prompt",
      "expected_output": "Description of expected result",
      "files": ["path/to/input/file.txt"],
      "assertions": [
        {
          "id": "a1",
          "name": "output-file-exists",
          "description": "The output CSV file was created",
          "type": "file_exists",
          "target": "output.csv"
        },
        {
          "id": "a2",
          "name": "contains-header-row",
          "description": "Output CSV has a header row",
          "type": "content_check",
          "target": "output.csv",
          "pattern": "^name,value,category"
        },
        {
          "id": "a3",
          "name": "llm-quality-check",
          "description": "Output format matches expected structure",
          "type": "llm",
          "rubric": "Does the output contain a properly formatted table with at least 3 columns?"
        }
      ]
    }
  ]
}
```

**Assertion types:**
- `file_exists` — checks that `target` file was created in the output directory
- `content_check` — checks that `target` file matches `pattern` (regex)
- `llm` — passes the output to an LLM with `rubric` as the evaluation question

---

## eval_metadata.json

One per eval directory. Written at run time by the skill-creator subagent.

```json
{
  "eval_id": 0,
  "eval_name": "descriptive-name-here",
  "prompt": "The user's task prompt",
  "assertions": [
    {
      "id": "a1",
      "name": "output-file-exists",
      "description": "The output file was created"
    }
  ]
}
```

`assertions` can be empty (`[]`) — filled in during step 2 of the eval run.

---

## grading.json

Written by the grader subagent after evaluating a run's outputs against its assertions.

```json
{
  "eval_id": 0,
  "run_id": "eval-descriptive-name-with_skill",
  "expectations": [
    {
      "text": "The output CSV file was created",
      "passed": true,
      "evidence": "Found output.csv in outputs/ directory with 47 rows"
    },
    {
      "text": "Output CSV has a header row",
      "passed": true,
      "evidence": "First line is 'name,value,category'"
    },
    {
      "text": "Values are properly normalized",
      "passed": false,
      "evidence": "Column 'value' contains strings like '1,234' instead of floats"
    }
  ],
  "overall_pass": false,
  "notes": "Main issue: numeric formatting. Output structure otherwise correct."
}
```

**Critical:** the `expectations` array must use exactly these field names: `text`, `passed`, `evidence`.
The viewer depends on these names. Do not use `name`/`met`/`details` or other variants.

---

## timing.json

Written when a subagent task completes. Captured from the task notification.

```json
{
  "total_tokens": 84852,
  "duration_ms": 23332,
  "total_duration_seconds": 23.3
}
```

---

## benchmark.json

Written by `scripts/aggregate_benchmark`. One per iteration.

```json
{
  "skill_name": "example-skill",
  "iteration": 1,
  "configs": [
    {
      "name": "with_skill",
      "display_name": "With Skill",
      "evals": [
        {
          "eval_id": 0,
          "eval_name": "descriptive-name",
          "pass_rate": 0.67,
          "assertions_total": 3,
          "assertions_passed": 2,
          "duration_ms": 23332,
          "total_tokens": 84852
        }
      ],
      "aggregate": {
        "pass_rate_mean": 0.67,
        "pass_rate_stddev": 0.0,
        "duration_ms_mean": 23332,
        "duration_ms_stddev": 0,
        "total_tokens_mean": 84852,
        "total_tokens_stddev": 0,
        "evals_count": 1
      }
    },
    {
      "name": "without_skill",
      "display_name": "Without Skill",
      "evals": [...],
      "aggregate": {...}
    }
  ],
  "delta": {
    "pass_rate": 0.12,
    "duration_ms": -4200,
    "total_tokens": -8000
  },
  "analyst_notes": "Optional analyst observations written by the analyzer agent."
}
```

**Ordering:** list `with_skill` (or `iteration_N`) before the baseline (`without_skill` or `old_skill`).

---

## feedback.json

Written by the eval viewer when the user clicks "Submit All Reviews".

```json
{
  "reviews": [
    {
      "run_id": "eval-descriptive-name-with_skill",
      "feedback": "The chart is missing axis labels",
      "timestamp": "2025-01-15T10:32:00Z"
    },
    {
      "run_id": "eval-another-name-with_skill",
      "feedback": "",
      "timestamp": "2025-01-15T10:33:00Z"
    }
  ],
  "status": "complete"
}
```

Empty feedback string means the user thought it was fine.

---

## trigger-eval.json

Used by `scripts/run_loop` for description optimization.

```json
[
  {
    "id": 1,
    "query": "The user's realistic task prompt",
    "should_trigger": true
  },
  {
    "id": 2,
    "query": "A query that should NOT trigger this skill",
    "should_trigger": false
  }
]
```

---

## Workspace directory structure

```
<skill-name>-workspace/
├── iteration-1/
│   ├── benchmark.json
│   ├── benchmark.md
│   ├── eval-<descriptive-name>/
│   │   ├── eval_metadata.json
│   │   ├── with_skill/
│   │   │   ├── outputs/          ← skill's produced files
│   │   │   ├── grading.json
│   │   │   └── timing.json
│   │   └── without_skill/
│   │       ├── outputs/
│   │       ├── grading.json
│   │       └── timing.json
│   └── eval-<another-name>/
│       └── ...
├── iteration-2/
│   └── ...
└── skill-snapshot/               ← optional, for "improving existing skill" baseline
    └── <original skill files>
```
