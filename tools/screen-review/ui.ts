// NEW: normalized full-page pins, explicit verdicts, and serialized local autosave for screen review.
import type { Feedback, Mutation, Pin, Screen, ScreenFeedback, Verdict } from "./types.ts";

function element<T extends HTMLElement = HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (!found) throw new Error(`Missing review control: ${id}`);
  return found as T;
}
const canvas = element("screen-canvas");
const page = element("screen-page");
const frame = element<HTMLIFrameElement>("mock");
const layer = element("pin-layer");
const note = element<HTMLTextAreaElement>("note");
const labels: Record<Verdict, string> = { pending: "Not reviewed", approved: "Approved", declined: "Declined" };
let screens: Screen[] = [];
let feedback: Feedback = { version: 1, screens: {} };
let current = "";
let active = "";
let pageHeight = 1889;
let layoutWidth = window.innerWidth;
let fitWidth = false;
let saving = false;
let shouldScroll = false;
const pending: Mutation[] = [];
const media = matchMedia("(prefers-color-scheme: dark)");
function theme(value: string) {
  document.documentElement.dataset.theme = value;
  element("theme").textContent = value === "dark" ? "Light mode" : "Dark mode";
}
theme(localStorage.getItem("screen-review-theme") ?? (media.matches ? "dark" : "light"));
media.addEventListener("change", () => {
  if (!localStorage.getItem("screen-review-theme")) theme(media.matches ? "dark" : "light");
});
element("theme").onclick = () => {
  const value = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
  localStorage.setItem("screen-review-theme", value);
  theme(value);
};
const clamp = (n: number) => Math.max(0, Math.min(1, n));
const review = (): ScreenFeedback => feedback.screens[current] ?? { verdict: "pending", at: "", pins: [] };

function status(message: string, retry = false) {
  element("save-status").textContent = message;
  element("retry").hidden = !retry;
}
function location() {
  const url = new URL(window.location.href);
  url.searchParams.set("screen", current);
  if (active) url.searchParams.set("pin", active);
  else url.searchParams.delete("pin");
  history.replaceState(null, "", url);
}
function mutate(command: Mutation) {
  const data = feedback.screens[command.screen] ?? { verdict: "pending", at: "", pins: [] };
  feedback.screens[command.screen] = data;
  if (command.action === "verdict") data.verdict = command.verdict;
  if (command.action === "delete-pin") data.pins = data.pins.filter((pin) => pin.id !== command.id);
  if (command.action === "pin") {
    const pin: Pin = { screen: command.screen, id: command.id, x: command.x, y: command.y, width: command.width, text: command.text, at: "" };
    const index = data.pins.findIndex((p) => p.id === command.id);
    if (index === -1) data.pins.push(pin);
    else data.pins[index] = pin;
  }
  pending.push(command);
  void flush();
}
async function flush() {
  if (saving) return;
  saving = true;
  try {
    while (pending.length) {
      status("Saving");
      const command = pending[0]!;
      const response = await fetch("/api/feedback", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(command) });
      const answer = await response.json();
      if (!response.ok) throw new Error(answer.error ?? "Save failed.");
      feedback.screens[command.screen]!.at = answer.at;
      if (command.action === "pin") {
        const pin = feedback.screens[command.screen]!.pins.find((p) => p.id === command.id);
        if (pin) pin.at = answer.at;
      }
      pending.shift();
    }
    status("Saved");
    element("error").hidden = true;
  } catch (error) {
    status("Not saved", true);
    element("error").textContent = `${error instanceof Error ? error.message : "Save failed."} Keep this page open and retry. Your note is still here.`;
    element("error").hidden = false;
  } finally {
    saving = false;
  }
}
function renderRail() {
  const list = element("screen-list");
  list.replaceChildren();
  for (const screen of screens) {
    const data = feedback.screens[screen.id];
    const verdict = data?.verdict ?? "pending";
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.className = `screen-item ${verdict}`;
    button.setAttribute("aria-current", screen.id === current ? "page" : "false");
    button.dataset.screen = screen.id;
    button.disabled = !screen.ready;
    const title = document.createElement("strong");
    title.textContent = screen.title;
    const meta = document.createElement("span");
    meta.className = "screen-meta";
    const dot = document.createElement("span");
    dot.className = "status-dot";
    meta.append(dot, document.createTextNode(screen.ready ? labels[verdict] + (data?.pins.length ? ` · ${data.pins.length} pin${data.pins.length === 1 ? "" : "s"}` : "") : "Not ready"));
    button.append(title, meta);
    button.onclick = () => select(screen.id);
    item.append(button);
    list.append(item);
  }
  const done = screens.filter((screen) => feedback.screens[screen.id]?.verdict && feedback.screens[screen.id]?.verdict !== "pending").length;
  element("progress").textContent = `${done} / ${screens.length} reviewed`;
}
function renderPins(resetEditor = true) {
  layer.replaceChildren();
  const data = review();
  const hasPins = data.pins.length > 0;
  element("review").dataset.hasPins = String(hasPins);
  element("feedback").hidden = !hasPins;
  element("mockups").hidden = !hasPins;
  const notes = element("notes");
  notes.replaceChildren();
  data.pins.forEach((pin, index) => {
    const marker = document.createElement("button");
    marker.className = `pin${pin.id === active ? " selected" : ""}`;
    marker.textContent = String(index + 1);
    marker.style.left = `${pin.x * 100}%`;
    marker.style.top = `${pin.y * 100}%`;
    marker.dataset.pin = pin.id;
    marker.setAttribute("aria-label", `Feedback pin ${index + 1}`);
    marker.onclick = (event) => { event.stopPropagation(); openPin(pin.id); };
    if (pin.width === layoutWidth) layer.append(marker);
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.className = "note-item";
    const number = document.createElement("span");
    number.className = "note-number";
    number.textContent = String(index + 1);
    const excerpt = document.createElement("span");
    excerpt.className = "note-excerpt";
    excerpt.textContent = pin.text || "New note";
    button.append(number, excerpt);
    button.onclick = () => { shouldScroll = true; openPin(pin.id); fit(); };
    item.append(button);
    notes.append(item);
  });
  const selected = data.pins.find((pin) => pin.id === active);
  element("editor").hidden = !selected;
  element("pin-count").textContent = data.pins.length ? `${data.pins.length} pin${data.pins.length === 1 ? "" : "s"}` : "No pins";
  if (selected) {
    element("pin-label").textContent = `Pin ${data.pins.indexOf(selected) + 1}`;
    if (resetEditor) note.value = selected.text;
    positionEditor();
  }
}
function positionEditor() {
  const editor = element("editor");
  const marker = layer.querySelector<HTMLElement>(".pin.selected");
  if (editor.hidden || !marker) return;
  const bounds = canvas.getBoundingClientRect();
  const pin = marker.getBoundingClientRect();
  const gap = 12;
  const margin = 8;
  editor.style.maxHeight = `${canvas.clientHeight - margin * 2}px`;
  const width = editor.offsetWidth;
  const height = editor.offsetHeight;
  const left = pin.left - bounds.left;
  const right = pin.right - bounds.left;
  const top = pin.top - bounds.top;
  const bottom = pin.bottom - bounds.top;
  const limit = (value: number, end: number) => Math.max(margin, Math.min(value, end - margin));
  let x: number;
  let y: number;
  if (canvas.clientWidth - right >= width + gap + margin) {
    x = right + gap;
    y = limit(top, canvas.clientHeight - height);
  } else if (left >= width + gap + margin) {
    x = left - gap - width;
    y = limit(top, canvas.clientHeight - height);
  } else {
    const below = canvas.clientHeight - bottom - gap - margin;
    const above = top - gap - margin;
    const down = below >= height || below >= above;
    editor.style.maxHeight = `${Math.max(1, down ? below : above)}px`;
    x = limit((left + right - width) / 2, canvas.clientWidth - width);
    y = down ? bottom + gap : top - gap - editor.offsetHeight;
  }
  editor.style.left = `${x}px`;
  editor.style.top = `${y}px`;
}
function openPin(id: string) {
  active = id;
  location();
  shouldScroll = true;
  fit();
  renderPins();
  note.focus({ preventScroll: true });
}
function fit() {
  const pin = review().pins.find((p) => p.id === active);
  const width = pin?.width ?? canvas.clientWidth;
  if (width !== layoutWidth) pageHeight = 1889;
  layoutWidth = width;
  const scale = fitWidth ? Math.min(1, canvas.clientWidth / layoutWidth) : 1;
  page.style.width = `${layoutWidth * scale}px`;
  page.style.height = `${pageHeight * scale}px`;
  frame.style.width = `${layoutWidth}px`;
  frame.style.height = `${pageHeight}px`;
  frame.style.transform = `scale(${scale})`;
  renderPins(false);
  if (shouldScroll && pin) {
    canvas.scrollTo({ top: pin.y * pageHeight * scale - canvas.clientHeight / 2, left: pin.x * layoutWidth * scale - canvas.clientWidth / 2 });
    shouldScroll = false;
  }
  positionEditor();
}
function select(id: string) {
  current = id;
  element<HTMLDetailsElement>("switcher").open = false;
  active = "";
  pageHeight = 1889;
  location();
  renderScreen();
}
function renderScreen() {
  const screen = screens.find((s) => s.id === current && s.ready);
  element("empty").hidden = !!screen;
  element("review").hidden = !screen;
  element("verdict-bar").hidden = !screen;
  element("inspector").hidden = !screen;
  if (!screen) {
    element("screen-title").textContent = "No ready screens";
    renderRail();
    return;
  }
  element("screen-title").textContent = screen.title;
  const index = screens.indexOf(screen);
  element("screen-position").textContent = `Screen ${index + 1} of ${screens.length}`;
  element<HTMLButtonElement>("previous").disabled = !screens.slice(0, index).some((s) => s.ready);
  element<HTMLButtonElement>("next").disabled = !screens.slice(index + 1).some((s) => s.ready);
  for (const part of ["why", "value", "dashboard"] as const) element(part).textContent = screen[part];
  frame.src = `/screens/${encodeURIComponent(current)}/index.html`;
  frame.title = `Proposed screen: ${screen.title}`;
  canvas.scrollTo(0, 0);
  renderVerdict();
  renderRail();
  renderPins();
  fit();
}
function renderVerdict() {
  const verdict = review().verdict;
  element("verdict-label").textContent = labels[verdict];
  element("approve").setAttribute("aria-pressed", String(verdict === "approved"));
  element("decline").setAttribute("aria-pressed", String(verdict === "declined"));
}
function addPin(clientX?: number, clientY?: number) {
  if (!current) return;
  const bounds = page.getBoundingClientRect();
  const visible = canvas.getBoundingClientRect();
  const x = clamp(((clientX ?? visible.left + visible.width / 2) - bounds.left) / bounds.width);
  const y = clamp(((clientY ?? visible.top + visible.height / 2) - bounds.top) / bounds.height);
  const id = crypto.randomUUID();
  mutate({ screen: current, action: "pin", id, x, y, width: layoutWidth, text: "" });
  renderRail();
  openPin(id);
}
layer.addEventListener("click", (event) => { if (event.target === layer) addPin(event.clientX, event.clientY); });
layer.addEventListener("keydown", (event) => {
  if (event.target === layer && (event.key === "Enter" || event.key === " ")) { event.preventDefault(); addPin(); }
});
element("add-pin").onclick = () => addPin();
element("close-note").onclick = () => { active = ""; location(); fit(); renderPins(); };
element("delete-pin").onclick = () => {
  mutate({ screen: current, action: "delete-pin", id: active });
  active = "";
  location();
  fit();
  renderPins();
  renderRail();
};
note.oninput = () => {
  const pin = review().pins.find((p) => p.id === active);
  if (pin) mutate({ screen: current, action: "pin", id: pin.id, x: pin.x, y: pin.y, width: pin.width, text: note.value });
  renderPins(false);
};
for (const verdict of ["approved", "declined"] as const) {
  element(verdict === "approved" ? "approve" : "decline").onclick = () => {
    mutate({ screen: current, action: "verdict", verdict: review().verdict === verdict ? "pending" : verdict });
    renderVerdict();
    renderRail();
  };
}
for (const [id, direction] of [["previous", -1], ["next", 1]] as const) {
  element(id).onclick = () => {
    const ready = screens.filter((s) => s.ready);
    const index = ready.findIndex((s) => s.id === current);
    const screen = ready[index + direction];
    if (screen) select(screen.id);
  };
}
element("zoom").onclick = () => {
  fitWidth = !fitWidth;
  element("zoom").textContent = fitWidth ? "Actual size" : "Fit width";
  element("zoom").setAttribute("aria-pressed", String(fitWidth));
  shouldScroll = true;
  fit();
};
element("retry").onclick = () => void flush();
new ResizeObserver(() => { shouldScroll = !!active; fit(); }).observe(canvas);
new ResizeObserver(positionEditor).observe(element("editor"));
canvas.addEventListener("scroll", positionEditor, { passive: true });
window.addEventListener("message", (event) => {
  if (event.source !== frame.contentWindow || event.data?.type !== "screen-review:size") return;
  const height = event.data.height;
  if (!Number.isFinite(height) || height < 1 || height > 100_000 || height === pageHeight) return;
  pageHeight = height;
  shouldScroll = !!active;
  fit();
});
try {
  const [proposalResponse, feedbackResponse] = await Promise.all([fetch("/api/screens"), fetch("/api/feedback")]);
  if (!proposalResponse.ok || !feedbackResponse.ok) throw new Error("Could not load the local review files.");
  screens = await proposalResponse.json();
  feedback = await feedbackResponse.json();
  const url = new URL(window.location.href);
  current = screens.find((s) => s.ready && s.id === url.searchParams.get("screen"))?.id ?? screens.find((s) => s.ready)?.id ?? "";
  active = url.searchParams.get("pin") ?? "";
  shouldScroll = !!active;
  renderScreen();
  status("Saved");
} catch (error) {
  element("screen-title").textContent = "Could not load screens";
  element("error").hidden = false;
  element("error").textContent = error instanceof Error ? error.message : "Reload to try again.";
  status("Load failed");
}
