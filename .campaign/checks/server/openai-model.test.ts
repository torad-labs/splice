// openai-model-get: GET /<command>/v1/models/<id> answers a key holder as the OpenAI API does: the model object of a
// model the command serves, a 404 in OpenAI's error shape for any other id, and a 401 without a key.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { COMMAND, MODEL, Server } from "./lib/server.ts";

let srv: Server;
let key = "";
beforeAll(async () => {
  srv = await Server.boot();
  key = srv.mint("ola@example.com").key;
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
}, 60_000);

const model = (id: string, k: string | null = key) =>
  fetch(`${srv.base}/${COMMAND}/v1/models/${encodeURIComponent(id)}`, { headers: k ? { authorization: `Bearer ${k}` } : {} });

test("a model the command serves is answered with its model object", async () => {
  const r = await model(MODEL);
  expect(r.status).toBe(200);
  const j = (await r.json()) as { id?: string; object?: string };
  expect(j.id).toBe(MODEL);
  expect(j.object).toBe("model");
}, 60_000);

test("any other id is a 404 in OpenAI's error shape", async () => {
  const r = await model("gpt-not-served");
  expect(r.status).toBe(404);
  const j = (await r.json()) as { error?: { message?: unknown } };
  expect(typeof j.error?.message).toBe("string");
}, 60_000);

test("without a key it is refused 401", async () => {
  expect((await model(MODEL, null)).status).toBe(401);
}, 60_000);
