// V4-421: which listed session the console wire probe reads the id-keyed session routes with. It took the
// first row of /api/sessions, and on the everyday daemon that row was a session nothing could resume
// (Eli's Telegram bridge registers and writes no transcript), so the resume read answered 404 and the
// probe went red for a listing defect, not a wire one. Rows now say `resumable`; the probe prefers one
// that does. A daemon older than the field is judged as before, and one that measured every row and found
// none resumable leaves the resume read unexercised instead of failed.
import { expect, test } from "bun:test";
import { pickId } from "../probes/console-wire-keys.ts";

const SESSIONS = { route: "/api/sessions", array: "sessions", field: "session_id" };
const RESUMABLE = { ...SESSIONS, where: { field: "resumable", equals: true } };

test("the first row that says resumable wins over an earlier one that says it is not", () => {
  const rows = [
    { session_id: "bridge", resumable: false },
    { session_id: "real", resumable: true },
    { session_id: "later", resumable: true },
  ];
  expect(pickId(rows, RESUMABLE)).toBe("real");
});

test("a daemon whose rows carry no resumable field is read as before, from its first row", () => {
  expect(pickId([{ session_id: "first" }, { session_id: "second" }], RESUMABLE)).toBe("first");
});

test("rows that all say they cannot be resumed leave the read without an id, naming what was wanted", () => {
  const picked = pickId([{ session_id: "a", resumable: false }, { session_id: "b", resumable: false }], RESUMABLE);
  expect(picked).toEqual({ empty: "/api/sessions has no sessions[] with resumable = true.session_id" });
});

test("a row that says resumable but names no session id is not an id", () => {
  expect(pickId([{ session_id: "", resumable: true }, { session_id: "named", resumable: true }], RESUMABLE)).toBe("named");
});

test("an ID source without a preference keeps taking the first row, whatever the rows say", () => {
  expect(pickId([{ session_id: "bridge", resumable: false }], SESSIONS)).toBe("bridge");
  expect(pickId([], SESSIONS)).toEqual({ empty: "/api/sessions has no sessions[].session_id" });
});
