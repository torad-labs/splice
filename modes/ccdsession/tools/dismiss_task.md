Withdraw a previously suggested follow-up task created with `spawn_task`.

Call this when a queued suggestion is now resolved in the current session, superseded by a better suggestion, or no longer relevant. To replace a chip, call `spawn_task` for the new suggestion first and then call `dismiss_task` with the old `task_id`.

Only pending suggestions can be withdrawn. If the user has already started, handled, or dismissed the task—or is starting it right now—the response reports its status; do not retry.
