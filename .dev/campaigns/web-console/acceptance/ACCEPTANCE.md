# The console, in the words of the person using it

What a splice user comes to the console to do, written as they would ask it. It is walked by eye, in
a normal-size browser window, as that person: for each question, can I do it, how many clicks did it
take, and did I have to already know where it was. Wherever the answer is "open a terminal", "read a
log" or "edit a TOML file", the console does not do it yet.

Owner and gate: Marlin (operator, 2026-09-27: Marlin approves every screen and every page).
Draft 1, 2026-09-27, merged from three sources: what splice can do today (source-capabilities.md),
what users of other proxy consoles can do (source-proxy-consoles.md), and how splice has actually
been used on the operator's machine (kept private, off this repo). Ordered by what splice users want
most (Splice-CPO demand research, 2026-09-25): limits, then models, then other plans inside Claude
Code, then seeing what several agents are doing. "(new)" marks something splice cannot do at all
today, in the console or anywhere else.

Who this person is: a developer who lives in Claude Code and also pays for ChatGPT, maybe Grok, Kimi,
Muse, an OpenRouter key or a local model. They set splice up once, then type `claudex` or
`claude-grok` where they used to type `claude`, and mostly forget splice exists. They come to the
console when a limit hits, when something looks wrong, when they want to change how an agent behaves,
or when they want to see what their agents are doing.

## 1. My limits, and what happens when I hit one

1. Can I see, on one page, how much of every plan I'm signed into is left right now: each Claude,
   ChatGPT, Grok, Kimi and Muse account, its 5-hour and 7-day windows, and when each resets?
2. Can I tell which account each of my running sessions is using right now?
3. Can I sign in another account of a provider I already use, from that same page, so splice can move
   to it when the first one runs out?
4. Can I keep several Claude accounts and see their limits next to my other providers?
5. Can I choose what happens when an account hits its limit: which account takes over next, whether
   to go back to my main account after it resets, or to stop and tell me? (new)
6. Can I see when splice moved one of my sessions to another account, and why?
7. Can I keep a session or a command on one account, and let it go again?
8. Can I remove an account, or rename it so I can tell mine apart?
9. Am I warned before I run out, not after, in the console or by a webhook?
10. When I'm out and have no other account, can I see where else I could continue (another provider,
    a local model) and continue there in one click?
11. Can I tell "out of credits" apart from "signed out"?
12. Can I see what today cost at API rates, per provider, model and session, and is it clear that on a
    subscription it's an estimate and nothing was billed?
13. Can I set a daily cap for a command, and choose whether it warns me or stops?
14. Can I see my usage over the last hour, day and week, and click from a spike to the sessions and
    requests behind it?

## 2. My models: which one, how big, how good

15. Can I see every model I can use, from every provider I'm signed into, in one list I can search and
    sort by context window and price?
16. Can I choose which model each of my commands uses when Claude Code asks for Opus, Sonnet or Haiku?
17. Can I change a model's context window, up to what it really supports, and have it apply without
    restarting anything?
18. Can I add a model a provider just released, and hide the ones I never use?
19. Can I tell whether a model is really served by its provider or local runtime, or only listed?
20. Can I compare two or three models side by side: context, price, speed?
21. Can I try a prompt against a model without touching my sessions?
22. Can I compare two sessions that ran on different models or with different instructions, and see
    what changed in what the model received and in how it answered? (new)

## 3. Bringing my other plans into Claude Code

23. Can I connect a new provider from the console (sign in with ChatGPT, Grok, Kimi or Muse in the
    browser, or paste an OpenRouter, DeepSeek or any OpenAI-compatible key) and get a command like
    `claudex` to type in my terminal?
24. Can I connect a local model (llama.cpp, Ollama, LM Studio, vLLM, a rented GPU) the same way?
25. Can I rename one of my commands, or remove one I no longer use?
26. Can I sign in again when a login expires, right where it tells me it expired?
27. Can I see whether each of my connections works right now, and fix the one that doesn't from where
    I see it?
28. Can I make my plain `claude` command go through splice, and undo that?
29. Can I see my API keys (masked), and add, replace or remove one?
30. Can I continue a session on another command, for example move it from Claude to GPT, in one click?

## 4. How my agents behave

31. Can I read the exact instructions each of my commands sends the model, including what I added?
32. Can I change what a command adds: add to Claude Code's own instructions, replace them, or remove
    parts of them, and see the result before it applies?
33. Can I set instructions for one repository, for every command I launch there, or for one command
    in one repository?
34. Can I see which instructions a specific session actually received?
35. Can I set instructions that apply only when a session compacts, per repository or per model, and
    see which ones a compaction used?
36. Can I see what a compaction did to a session, and why one failed?
37. Can I turn code mode on or off for ChatGPT, with a sentence telling me what it does?
38. Can I keep versions of my instructions and go back to an earlier one? (new)

## 5. What my agents are doing

39. Can I see all my sessions by the names I know them by, grouped by repository and by provider,
    and tell which are working, waiting on me, stuck or finished?
40. Can I open a session and read it like a chat: my messages, its answers, the tools it ran?
41. Can I search my sessions for something that was said or done? (new)
42. Can I see which of my sessions talked to each other, and read those messages as a conversation,
    per repository or per piece of work, with each message under its sender's name?
43. Can I send a message to one of my sessions from the console?
44. Can I stop a turn that's running?
45. Can I set up a team (sessions on different models working one goal), edit it, find it again and
    archive it?
46. Can I see what each session and each team used and cost?

## 6. When something looks wrong

47. Can I see every request going through splice, newest first, and filter it by command, model,
    session, status and time?
48. Can I click one and see everything the model received (instructions, messages, tools) and
    everything it sent back, with tokens, time, cache and cost?
49. Can I see on each row, in plain words, whether it was retried, moved to another account, or
    failed?
50. Can I see where a slow turn spent its time: waiting on the provider, retrying, streaming?
51. Can I tell whether a quiet turn is still working or hung, and stop it?
52. Does every error tell me in plain words what happened and what to do?
53. Can I re-run a request to see if a fix worked?
54. Can I read splice's own log for one command, filtered, following live?
55. Can I run the health check and apply each fix from where it's shown?
56. Can I make a report I can share that hides my private details?
57. Can I link someone, or myself later, to one request, one session or one filtered view?

## 7. Keeping splice itself healthy

58. Can I see which version is running, whether a newer one exists, and upgrade or roll back without
    cutting turns in flight?
59. Can I tell when a change I made needs a restart, and restart safely when it does?
60. Can I see which tool servers (MCP) my sessions share, and leave one out?

## 8. My data

61. Can I see what splice keeps on my disk and for how long, and delete it?
62. Can I choose whether full request content is recorded for a command, with a sentence telling me
    exactly what that keeps?
63. Can I get my configuration back from before a change?

## 9. Finding my way

64. Can I find any setting by typing what I'm looking for, and is each one named for what it does,
    with its current value and a sentence explaining it?
65. Can I change a thing where I'm looking at it: a command's model where I see the command, an
    account where I see its usage, a team where I see the team?
66. Can I search everything (sessions, requests, models, settings) from one box? (new)
67. Does every page load fast on a busy week, and tell me when it can't load something?

## What users of other consoles hate, and this one must not do

- A view that loads slower the more history there is.
- A detail pane that comes up blank without saying why.
- A limit that shows in the console but doesn't actually enforce.
- An error that doesn't explain itself.
- Safe behavior (retrying, waiting) that only happens if I opt in.
- Big payloads dumped raw so the page crawls.
- Views with no default time range that time out.
- A decision that costs money, explained only in a help article.
- Having to remember which tab a setting lives under.
