Queue an out-of-scope follow-up issue as a suggested task for the user without interrupting your current work.

Use this when you spot a concrete issue worth addressing separately (for example dead code, stale documentation, missing tests, a confirmed bug, or a security gap) that would expand the scope of the current change. Avoid flagging low-confidence hunches or trivial inline edits.

The user sees a suggested-task chip and can start it in a new session, run it in this session, or dismiss it. Your turn continues immediately. Provide a self-contained `prompt` with file paths and full context so a fresh session can execute it without this transcript. Save the returned `task_id` in case you need to withdraw the suggestion later via `dismiss_task`.
