## Module: tool

- A small tool the person uses in place: a calculator, a converter, a list to filter or sort, a timeline to scrub.
- Put its data in the script as a literal. It cannot fetch anything; a tool that must follow data that changes outside
  the conversation watches a feed (module `live`).
- Give every input a visible label, and update the result on `input`, not only on a button.
- Write results with `textContent`, never by building HTML from what the person typed.
- When the person's choice should come back to you, add a button that calls `sendPrompt("...")` with a message that
  states the choice in full, such as "Use 3 replicas and a 30 s timeout".
- State lives in memory for as long as the frame does; do not count on storage.
