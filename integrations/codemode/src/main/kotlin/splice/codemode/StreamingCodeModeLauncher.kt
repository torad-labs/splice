// NEW: native lexical accessors are addressed explicitly so awaits cannot lose a dynamic scope.
package splice.codemode

internal const val STREAMING_CODE_MODE_LAUNCHER: String = """
(base, host, EXIT, describe, evalProjection, rawSeal) => {
  const bindings = new Map();
  const root = globalThis;
  const dependencies = new Map();
  const nativeJSON = JSON;
  const nativePromise = Promise;
  const nativeAll = Promise.all;
  const nativeAllSettled = Promise.allSettled;
  const nativeThen = Promise.prototype.then;
  const arrayPrototype = Array.prototype;
  const iteratorSymbol = Symbol.iterator;
  const nativeIterator = arrayPrototype[iteratorSymbol];
  const sealedGlobals = new Set(nativeJSON.parse(rawSeal));
  const NativeFunction = Function;
  const NativeReferenceError = ReferenceError;
  const values = new Proxy(Object.create(null), {
    get(_, key) {
      const binding = bindings.get(key);
      if (binding) return binding.get();
      if (Object.prototype.hasOwnProperty.call(base, key)) return base[key];
      if (key in root) return root[key];
      throw new NativeReferenceError(String(key) + " is not defined");
    },
    set(_, key, value) {
      const binding = bindings.get(key);
      if (binding) binding.set(value);
      else if (Object.prototype.hasOwnProperty.call(base, key)) base[key] = value;
      else if (key in root) root[key] = value;
      else throw new NativeReferenceError(String(key) + " is not defined");
      return true;
    }
  });
  const scope = Object.freeze({
    values,
    evalSource(code, scopeName, locals) {
      return typeof code === "string" ? evalProjection(code, scopeName, locals) : code;
    },
    prior(name) {
      const old = bindings.get(name);
      return old && (old.kind === "var" || old.kind === "function") ? old.get() : undefined;
    },
    typeOf(name) {
      if (bindings.has(name) || Object.prototype.hasOwnProperty.call(base, name) || name in root) {
        return typeof values[name];
      }
      return "undefined";
    }
  });
  let running = false;
  let stopped = false;
  let returned = "";
  let scriptError = null;
  let finished = false;
  const finish = error => {
    if (finished) return;
    finished = true;
    const failure = [scriptError, error].filter(Boolean).join("\n");
    if (failure && returned) host.log(returned);
    host.complete(failure || returned, !!failure);
  };
  const capture = (name, kind, get, set) => {
    const old = bindings.get(name);
    const mutable = value => value === "var" || value === "function";
    if (old && (!mutable(kind) || !mutable(old.kind))) {
      throw new SyntaxError("Identifier '" + name + "' has already been declared");
    }
    bindings.set(name, {kind, get, set});
  };
  const unique = (source, stem) => {
    let name = stem;
    while (source.includes(name)) name += "_";
    return name;
  };
  return Object.freeze({
    running() { return running; },
    stopped() { return stopped; },
    ready(rawReads, rawBindings, rawDependencies, rawIntrinsics) {
      const intrinsics = new Map(Object.entries(nativeJSON.parse(rawIntrinsics)));
      const primitive = name => {
        const binding = bindings.get(name);
        if (!binding) return false;
        const value = binding.get();
        return value === null || (typeof value !== "object" && typeof value !== "function");
      };
      const nativeBatch = name => name !== "Promise" || (
        values.Promise === nativePromise && nativePromise.all === nativeAll &&
        nativePromise.allSettled === nativeAllSettled && nativePromise.prototype.then === nativeThen &&
        arrayPrototype[iteratorSymbol] === nativeIterator
      );
      const declared = new Map(nativeJSON.parse(rawBindings).map(binding => [binding.name, binding.kind]));
      const incoming = new Map(Object.entries(nativeJSON.parse(rawDependencies)));
      const visited = new Set();
      const ready = name => {
        if (visited.has(name)) return true;
        visited.add(name);
        const kind = declared.get(name) || (bindings.get(name) || {}).kind;
        // Native globals can be shadowed and function declarations replaced by source not yet received.
        // Keep that suffix unexecuted until EOF, when one native body sees the complete declaration set.
        if (kind === "function") return false;
        const found = declared.has(name) || bindings.has(name) ||
          Object.prototype.hasOwnProperty.call(base, name) ||
          (sealedGlobals.has(name) && intrinsics.has(name) && name in root &&
            nativeBatch(name) && intrinsics.get(name).every(primitive));
        return found && (incoming.get(name) || dependencies.get(name) || []).every(ready);
      };
      return nativeJSON.parse(rawReads).every(ready);
    },
    run(source, rawBindings, scopeName, rawDependencies, sourceEnded, sourceError) {
      for (const [name, reads] of Object.entries(nativeJSON.parse(rawDependencies))) {
        dependencies.set(name, reads);
      }
      const captureName = unique(source, "__splice_capture");
      const endName = unique(source, "__splice_end");
      const declared = nativeJSON.parse(rawBindings);
      const seeds = declared.filter(binding => binding.kind === "var").map(binding =>
        "var " + binding.name + " = " + scopeName + ".prior(" + nativeJSON.stringify(binding.name) + ");"
      ).join("\n");
      const exports = declared.map(binding =>
        captureName + "(" + nativeJSON.stringify(binding.name) + "," + nativeJSON.stringify(binding.kind) +
        ",() => " + binding.name + ", value => { " + binding.name + " = value; });"
      ).join("\n");
      const fallthrough = Object.freeze({});
      running = true;
      try {
        const factory = new NativeFunction(scopeName, captureName, endName,
          "\"use strict\"; return function (tools, console, text, exit, ALL_TOOLS) { return (async () => {\n" +
          seeds + "\n" + exports + "\n" + source + "\nreturn " + endName + ";\n})(); };");
        const program = factory(scope, capture, fallthrough);
        Promise.resolve(program(base.tools, base.console, base.text, base.exit, base.ALL_TOOLS)).then(
          value => {
            running = false;
            if (value !== fallthrough) {
              returned = value === undefined ? "" : String(value);
              stopped = true;
            }
            if (sourceEnded) finish(sourceError);
          },
          error => {
            running = false;
            stopped = true;
            if (error !== EXIT) scriptError = describe(error);
            if (sourceEnded) finish(sourceError);
          }
        );
      } catch (error) {
        running = false;
        stopped = true;
        scriptError = describe(error);
        if (sourceEnded) finish(sourceError);
      }
    },
    reject(error) { stopped = true; scriptError = error; },
    finish
  });
}
"""
