// The top bar belongs to the console. Sections get their own DOM, requests and lifetime.
"use strict";

const CONSOLE = (() => {
  const sections = new Map(), scripts = new Map(), templates = new Map(), sheets = new Map();
  let mounted = null, generation = 0;
  const fileOf = (url) => PAGES.find(([file]) => url.pathname.endsWith(`/${file}`))?.[0];

  function lifetime(root, extras) {
    const abort = new AbortController(), timers = new Set(), intervals = new Set(), frames = new Set(), observers = new Set(), listeners = [];
    const app = document.querySelector(".app"), retiredApp = document.createElement("div");
    const roots = [root, ...extras];
    let active = true, pageWindow;
    const owns = (node) => roots.some((r) => r === node || r.contains(node));
    const queryAll = (selector) => selector === ".app" ? [active ? app : retiredApp] : roots.flatMap((r) => [
      ...(r.matches(selector) ? [r] : []), ...r.querySelectorAll(selector),
    ]);
    function listen(target, type, fn, options) {
      const wrapped = (event) => {
        if (!active) return;
        if (target === document && /^(click|input|change|focusin|focusout)$/.test(type) && !owns(event.target)) return;
        if (typeof fn === "function") fn.call(target, event); else fn.handleEvent(event);
      };
      target.addEventListener(type, wrapped, options);
      listeners.push({ target, type, fn, wrapped, options });
    }
    function unlisten(target, type, fn, options) {
      const capture = (value) => typeof value === "boolean" ? value : Boolean(value?.capture);
      for (let i = listeners.length - 1; i >= 0; i--) {
        const item = listeners[i];
        if (item.target !== target || item.type !== type || item.fn !== fn || capture(item.options) !== capture(options)) continue;
        target.removeEventListener(type, item.wrapped, item.options);
        listeners.splice(i, 1);
      }
    }
    const bound = new Map();
    const pageDocument = new Proxy(document, {
      get(target, key) {
        if (key === "querySelector") return (selector) => queryAll(selector)[0] || null;
        if (key === "querySelectorAll") return queryAll;
        if (key === "getElementById") return (id) => queryAll(`#${CSS.escape(id)}`)[0] || null;
        if (key === "addEventListener") return (type, fn, options) => listen(target, type, fn, options);
        if (key === "removeEventListener") return (type, fn, options) => unlisten(target, type, fn, options);
        if (key === "defaultView") return pageWindow;
        const value = Reflect.get(target, key, target);
        if (typeof value !== "function") return value;
        if (!bound.has(key)) bound.set(key, value.bind(target));
        return bound.get(key);
      },
    });
    const scope = {
      root, document: pageDocument, API: API.scoped(abort.signal),
      addEventListener: (type, fn, options) => listen(window, type, fn, options),
      removeEventListener: (type, fn, options) => unlisten(window, type, fn, options),
      setTimeout(fn, delay, ...args) {
        if (!active) return 0;
        const id = window.setTimeout(() => { timers.delete(id); if (active) fn(...args); }, delay);
        timers.add(id); return id;
      },
      clearTimeout(id) { timers.delete(id); window.clearTimeout(id); },
      setInterval(fn, delay, ...args) {
        if (!active) return 0;
        const id = window.setInterval(() => { if (active) fn(...args); }, delay);
        intervals.add(id); return id;
      },
      clearInterval(id) { intervals.delete(id); window.clearInterval(id); },
      requestAnimationFrame(fn) {
        if (!active) return 0;
        const id = window.requestAnimationFrame((time) => { frames.delete(id); if (active) fn(time); });
        frames.add(id); return id;
      },
      cancelAnimationFrame(id) { frames.delete(id); window.cancelAnimationFrame(id); },
      fetch(input, options = {}) {
        const signal = options.signal ? AbortSignal.any([abort.signal, options.signal]) : abort.signal;
        return window.fetch(input, { ...options, signal });
      },
    };
    for (const key of ["IntersectionObserver", "ResizeObserver"]) {
      const Native = window[key];
      if (!Native) continue;
      scope[key] = class extends Native {
        constructor(callback, options) {
          super((...args) => { if (active) callback(...args); }, options);
          observers.add(this);
        }
        disconnect() { observers.delete(this); super.disconnect(); }
      };
    }
    const windowMethods = new Map();
    pageWindow = new Proxy(window, {
      get(target, key) {
        if (key === "document") return pageDocument;
        if (Object.hasOwn(scope, key)) return scope[key];
        const value = Reflect.get(target, key, target);
        if (typeof value !== "function") return value;
        if (!windowMethods.has(key)) windowMethods.set(key, value.bind(target));
        return windowMethods.get(key);
      },
    });
    scope.window = pageWindow;
    scope.KNOBS = createKnobs(scope);
    return {
      scope,
      // Leaving a page cancels its requests at the click, so the next page never queues behind them.
      leave() { abort.abort(); },
      dispose() {
        active = false; abort.abort();
        for (const id of timers) window.clearTimeout(id);
        for (const id of intervals) window.clearInterval(id);
        for (const id of frames) window.cancelAnimationFrame(id);
        for (const observer of [...observers]) observer.disconnect();
        for (const { target, type, wrapped, options } of listeners) target.removeEventListener(type, wrapped, options);
        app.inert = false;
        extras.forEach((node) => node.remove());
      },
    };
  }

  function template(doc) {
    const main = doc.querySelector("main.page");
    if (!main) throw new Error("This page has no content");
    return {
      title: doc.title, main: main.cloneNode(true),
      extras: [...doc.body.children].filter((el) => !el.matches(".app,script")).map((el) => el.cloneNode(true)),
      css: [...doc.querySelectorAll('link[rel="stylesheet"]')].map((el) => el.getAttribute("href")).filter((href) => href !== "console.css"),
    };
  }
  async function load(file) {
    if (!templates.has(file)) {
      const response = await fetch(file);
      if (!response.ok) throw new Error(`Section returned ${response.status}`);
      templates.set(file, template(new DOMParser().parseFromString(await response.text(), "text/html")));
    }
    if (!sections.has(file)) {
      if (!scripts.has(file)) scripts.set(file, new Promise((resolve, reject) => {
        const script = document.createElement("script");
        script.src = file.replace(/\.html$/, ".js");
        script.onload = () => resolve();
        script.onerror = () => { scripts.delete(file); script.remove(); reject(new Error("Page could not load")); };
        document.body.append(script);
      }));
      await scripts.get(file);
    }
    if (!sections.has(file)) throw new Error("Page did not start");
    return templates.get(file);
  }
  // Each page stylesheet loads once and stays in the head, switched on only while its page is shown.
  function sheet(href) {
    if (!sheets.has(href)) sheets.set(href, new Promise((resolve, reject) => {
      const link = document.createElement("link");
      link.rel = "stylesheet"; link.href = href; link.media = "not all"; link.dataset.consoleSection = "";
      link.onload = () => resolve(link);
      link.onerror = () => { sheets.delete(href); link.remove(); reject(new Error("Page styles could not load")); };
      document.head.append(link);
    }));
    return sheets.get(href);
  }
  const styles = (css) => Promise.all(css.map(sheet));
  function select(file, title) {
    document.title = title;
    for (const link of document.querySelectorAll(".side .nav a")) {
      if (link.getAttribute("href") === file) link.setAttribute("aria-current", "page");
      else link.removeAttribute("aria-current");
    }
  }
  function mount(file, main, extras) {
    const current = lifetime(main, extras);
    mounted = { ...current, file, main };
    sections.get(file)(current.scope);
  }
  async function navigate(address, options = {}) {
    const url = new URL(address, location.href), file = fileOf(url);
    if (url.origin !== location.origin || !file) { location.href = url.href; return; }
    if (url.href === location.href && mounted?.file === file && !options.restore) return;
    const ticket = ++generation;
    mounted?.leave();
    try {
      const next = await load(file);
      if (ticket !== generation) return;
      const links = await styles(next.css);
      if (ticket !== generation) return;
      const previous = mounted, main = next.main.cloneNode(true), extras = next.extras.map((node) => node.cloneNode(true));
      previous.dispose();
      previous.main.replaceWith(main);
      document.body.append(...extras);
      for (const link of document.querySelectorAll("link[data-console-section]")) link.media = links.includes(link) ? "all" : "not all";
      if (!options.restore) history.pushState(null, "", url.href);
      select(file, next.title);
      window.scrollTo(0, 0);
      mount(file, main, extras);
      if (options.focus !== false) { main.tabIndex = -1; main.focus({ preventScroll: true }); }
    } catch (error) {
      if (ticket !== generation) return;
      const main = document.querySelector("main.page");
      main.querySelector(".route-error")?.remove();
      const note = document.createElement("p");
      note.className = "route-error empty"; note.setAttribute("role", "alert");
      note.textContent = `Could not open ${PAGES.find(([f]) => f === file)?.[1] || "page"}: ${error.message}. Try the link again.`;
      main.prepend(note);
    }
  }
  window.addEventListener("click", (event) => {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    const link = event.target.closest?.("a[href]");
    if (!link || link.hasAttribute("download") || (link.target && link.target !== "_self")) return;
    const url = new URL(link.href, location.href);
    if (url.origin !== location.origin || !fileOf(url)) return;
    if (url.pathname === location.pathname && url.search === location.search && url.hash !== location.hash) return;
    event.preventDefault();
    navigate(url.href, { focus: !link.closest(".side") });
  });
  window.addEventListener("popstate", () => navigate(location.href, { restore: true }));
  window.addEventListener("DOMContentLoaded", () => {
    const file = fileOf(new URL(location.href)) || "accounts.html";
    templates.set(file, template(document));
    for (const link of document.querySelectorAll('link[rel="stylesheet"]')) {
      if (link.getAttribute("href") === "console.css") continue;
      link.dataset.consoleSection = "";
      sheets.set(link.getAttribute("href"), Promise.resolve(link));
    }
    const extras = [...document.body.children].filter((el) => !el.matches(".app,script"));
    mount(file, document.querySelector("main.page"), extras);
  });
  return { page: (file, setup) => sections.set(file, setup), navigate };
})();
