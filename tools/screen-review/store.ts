// NEW: discover local mock folders and atomically retain private verdicts and normalized pins.
import { existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, renameSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import type { Feedback, Mutation, Screen, ScreenFeedback } from "./types.ts";

const folder = /^\d+-[^/\\]+$/;
const verdicts = new Set(["pending", "approved", "declined"]);
const text = (value: unknown): value is string => typeof value === "string" && value.length <= 20_000;
const fraction = (value: unknown): value is number =>
  typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 1;
const width = (value: unknown): value is number => typeof value === "number" && Number.isInteger(value) && value > 0 && value <= 100_000;
const plainFile = (path: string) => existsSync(path) && lstatSync(path).isFile() && !lstatSync(path).isSymbolicLink();

export function discover(screensRoot: string): Screen[] {
  if (!existsSync(screensRoot)) return [];
  return readdirSync(screensRoot, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && folder.test(entry.name))
    .sort((a, b) => a.name.localeCompare(b.name, "en", { numeric: true }))
    .map(({ name }) => {
      const root = join(screensRoot, name);
      const pending: Screen = { id: name, title: name, ready: false, why: "", value: "", dashboard: "" };
      try {
        if (!plainFile(join(root, "index.html")) || !plainFile(join(root, "justification.json"))) return pending;
        const proposal = JSON.parse(readFileSync(join(root, "justification.json"), "utf8"));
        if (!proposal || ["title", "why", "value", "dashboard"].some((key) => !text(proposal[key]) || !proposal[key].trim())) return pending;
        return { id: name, title: proposal.title, ready: true, why: proposal.why, value: proposal.value, dashboard: proposal.dashboard };
      } catch {
        // A folder can arrive in parts; it cannot make the completed proposals unavailable.
        return pending;
      }
    });
}

function validFeedback(value: unknown): value is Feedback {
  if (!value || typeof value !== "object") return false;
  const doc = value as Feedback;
  if (doc.version !== 1 || !doc.screens || Array.isArray(doc.screens) || typeof doc.screens !== "object") return false;
  return Object.entries(doc.screens).every(([id, review]) =>
    folder.test(id) && review && verdicts.has(review.verdict) && text(review.at) &&
    Array.isArray(review.pins) && review.pins.every((pin) =>
      pin && pin.screen === id && text(pin.id) && fraction(pin.x) && fraction(pin.y) && width(pin.width) && text(pin.text) && text(pin.at)));
}

export function parseMutation(value: unknown): Mutation {
  if (!value || typeof value !== "object") throw new Error("A feedback action is required.");
  const m = value as Mutation;
  if (!text(m.screen) || !folder.test(m.screen)) throw new Error("Choose a valid screen.");
  if (m.action === "verdict" && verdicts.has(m.verdict)) return m;
  if (m.action === "delete-pin" && text(m.id) && m.id.length > 0) return m;
  if (m.action === "pin" && text(m.id) && m.id.length > 0 && fraction(m.x) && fraction(m.y) && width(m.width) && text(m.text)) return m;
  throw new Error("Choose a verdict or provide a pin with coordinates between zero and one.");
}

/** Synchronous atomic writes serialize mutations on Bun's event loop; publish memory only after disk succeeds. */
export class ReviewStore {
  private feedback: Feedback;
  constructor(private readonly file: string, private readonly now = () => new Date().toISOString()) {
    const initial = existsSync(file) ? JSON.parse(readFileSync(file, "utf8")) : { version: 1, screens: {} };
    if (!validFeedback(initial)) throw new Error("Feedback is invalid. The existing file was left unchanged.");
    this.feedback = initial;
  }
  read(): Feedback {
    return structuredClone(this.feedback);
  }
  apply(mutation: Mutation): string {
    const next = this.read();
    const at = this.now();
    const review: ScreenFeedback = next.screens[mutation.screen] ?? { verdict: "pending", at, pins: [] };
    next.screens[mutation.screen] = review;
    review.at = at;
    if (mutation.action === "verdict") review.verdict = mutation.verdict;
    if (mutation.action === "delete-pin") review.pins = review.pins.filter((pin) => pin.id !== mutation.id);
    if (mutation.action === "pin") {
      const pin = { screen: mutation.screen, id: mutation.id, x: mutation.x, y: mutation.y, width: mutation.width, text: mutation.text, at };
      const index = review.pins.findIndex((p) => p.id === pin.id);
      if (index === -1) review.pins.push(pin);
      else review.pins[index] = pin;
    }
    mkdirSync(dirname(this.file), { recursive: true, mode: 0o700 });
    const temporary = `${this.file}.tmp`;
    writeFileSync(temporary, JSON.stringify(next, null, 2) + "\n", { mode: 0o600 });
    renameSync(temporary, this.file);
    this.feedback = next;
    return at;
  }
}
