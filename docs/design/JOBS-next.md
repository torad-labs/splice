# What each console page is for

<!-- Rendered from each page's `job` in console-next/src/pages/*/coverage.ts. tests/jobs.test.ts fails when
     this file differs; edit the declarations, then `vitest run -u tests/jobs.test.ts` in console-next/. -->

Each page answers one question. An action with a row in brackets is not built yet; the row builds it.

## fleet

**Question.** Are my plans up, signed in and within their limits?

**Leaves knowing.** Each plan's state, its windows and accounts, and the one act that fixes what is wrong.

**Actions.**

- Start, stop or restart a plan
- Sign in an account
- Switch, refresh, rename or remove an account
- Add a plan
- Add models to a plan
- Compare a plan’s models with what its provider publishes
- Read a plan's log
- Stop a running turn
- Reorder the cards

## needs

**Question.** What needs me right now?

**Leaves knowing.** Every plan or session that is stuck, out of quota, signed out or unhealthy, each with the one act that clears it.

**Actions.**

- Sign a plan in again
- Switch to another account
- Start or restart a plan
- Fix a problem the health check found
- Stop a turn that is stuck
- Restart splice

## projects

**Question.** What runs in this repo, and what governs it?

**Leaves knowing.** The sessions and teams here, the rules and files that apply, and the repo's standing prompt and compaction rule.

**Actions.**

- Edit the repo's standing prompt and compaction rule

## session

**Question.** What is this session doing, and who has it handed work to?

**Leaves knowing.** Its conversation and tool calls, its hand-offs to and from other sessions, and the command that resumes it.

**Actions.**

- Read the conversation
- Filter to hand-offs
- Copy the resume command for a plan
- Stop the turn

## sessions

**Question.** What is running, and where?

**Leaves knowing.** Every session grouped by state, plan, repo or team, and which ones need me.

**Actions.**

- Search and group sessions
- Reorder the cards
- Open a session
- Create or edit a team
- Open a repo's page
- Stop a session's turn

## settings

**Question.** How is splice set up, and is it healthy?

**Leaves knowing.** Every setting and the configuration file, what needs a restart, the version and health of the install, and what is kept on this computer.

**Actions.**

- Change a setting
- Edit the configuration file
- Set a plan's instructions
- Wrap or unwrap the claude command
- Share tools between plans
- Store or remove an API key
- Fix a problem the health check found
- Upgrade or roll back splice
- Restart splice
- Try a plan with one prompt
- Clear what splice keeps

## teams

**Question.** Who on the team is working, and who is waiting on whom?

**Leaves knowing.** A team's seats and who sits in them, the day's talk and activity, and what the team has spent.

**Actions.**

- Create or edit a team
- Bind or unbind a session to a seat
- Archive a team

## turns

**Question.** How are my turns going, and what happened in one?

**Leaves knowing.** Finished turns with their outcome, time and cost, and for one turn its conversation, its request and answer, and what was sent to the plan.

**Actions.**

- Filter and read finished turns
- Read a plan’s request and answer for a turn
- Read what a plan sent upstream
- Turn capture on for a plan

## usage

**Question.** What have I used, and what will it cost?

**Leaves knowing.** Each plan's windows and pace, what the day and week cost, and the budgets and alerts that guard it.

**Actions.**

- Choose the window
- Set a daily budget for a plan
- Set alerts and test them
