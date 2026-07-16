---
name: code-formatter
description: >-
  This skill should be used when the user asks to "format code", "fix code
  style", "run the formatter", "apply formatting rules", or "clean up this
  file". It formats TypeScript, JavaScript, and Python files using the
  project's configured formatters (prettier for TS/JS, black for Python).
user-invocable: true
argument-hint: "[file or directory to format]"
---

# Code Formatter

Applies the project's configured formatters in the correct order.

## Process

1. Detect the language from the file extension (`.ts`, `.tsx`, `.js`, `.jsx`
   → prettier; `.py` → black).
2. Run the formatter with the project config file if present (`prettier.config.js`,
   `pyproject.toml`).
3. Report which files changed and which were already formatted.

## Output

A list of formatted files, or "No changes needed" if all files were already
correctly formatted. Exit non-zero if the formatter itself fails.
