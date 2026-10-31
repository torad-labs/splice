# Explicit CodeMode interruption

`POST /api/heads/{head}/sessions/{session}/interrupt` requires the management or console bearer and a JSON body with exactly one string field, `conversation_key`.
The head resolves through the normal canonical key or unique wrapper name. The session and conversation key must identify a conversation already owned by that head's live CodeMode bridge; the route never opens a native session or searches transcripts. Conversation keys are exact strings, including an empty string when that was the opening conversation identity.

The response names the canonical `head`, `session`, `conversation_key`, and every addressed native cell:

```json
{"head":"codex","session":"client-session","conversation_key":"exact-key","cells":[{"cell_id":"native-cell","terminated":true,"failure":null}]}
```

An owned idle conversation returns HTTP 200 with `cells: []`. Unknown heads or unavailable native conversation ownership return 404. A stopped head, a head with no live CodeMode capability, or any reported failed termination returns 409. Failed termination responses retain the complete cell outcomes so callers can see partial success and retry failures. Invalid request bodies return 400; anonymous and session-scoped bearers are refused.

Only active cells in the addressed native conversation are interrupted. Completed store values and the native session survive, and a failed termination remains retryable. Other conversations and the head remain running.

This is an explicit control operation. Foreground HTTP cancellation, a new user message, and interruption text markers do not invoke it. Ordinary Claude Esc does not currently provide an authoritative, distinct stop reason on the provider connection; this endpoint does not claim to recognize it.
