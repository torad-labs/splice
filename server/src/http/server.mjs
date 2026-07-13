// HTTP plumbing shared by both proxies: server construction (loopback bind ONLY),
// body reading, and the write-safety/framing helpers the SSE emitter builds on.
import http from 'node:http';

export async function readBody(req) {
  let raw = '';
  for await (const chunk of req) raw += chunk;
  return raw;
}

export async function readBodyBuffer(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  return Buffer.concat(chunks);
}

export function sendJson(res, status, obj) {
  if (res.headersSent || res.writableEnded) return;
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(obj));
}

/** Cork-coalescing writer: multiple SSE frames land in one syscall under delta
 * storms. onWriteError fires once when the client is gone. */
export function makeSafeWriter(res, { onFirstByte, onWriteError } = {}) {
  let firstByteSeen = false;
  return (chunk) => {
    if (res.writableEnded || res.destroyed) return false;
    try {
      if (!firstByteSeen) {
        firstByteSeen = true;
        onFirstByte?.();
      }
      if (typeof res.cork === 'function') res.cork();
      res.write(chunk);
      if (typeof res.uncork === 'function') process.nextTick(() => { try { res.uncork(); } catch { /* ignore */ } });
      return true;
    } catch {
      onWriteError?.();
      return false;
    }
  };
}

/** End the response WITHOUT racing the deferred uncork: res.end() in the same
 * tick as a corked write puts the terminal chunk on the wire BEFORE the
 * buffered SSE frame (verified by raw socket capture in v25) — drain the cork
 * queue synchronously, then end. */
export function endResponse(res) {
  try {
    while (typeof res.writableCorked === 'number' && res.writableCorked > 0) res.uncork();
    res.end();
  } catch { /* ignore */ }
}

/**
 * Build a tuned proxy server. The 127.0.0.1 bind lives HERE and only here —
 * these are loopback-only instruments (wall: loopback-bind-only).
 */
export function createProxyServer(handler) {
  const server = http.createServer(handler);
  server.maxConnections = 256;
  server.keepAliveTimeout = 120_000;
  server.headersTimeout = 125_000;
  server.requestTimeout = 0; // long streams; upstream timeouts are separate
  // Disable Nagle on every accepted socket — lower latency for SSE deltas
  server.on('connection', (socket) => {
    try {
      socket.setNoDelay(true);
      socket.setKeepAlive(true, 30_000);
    } catch { /* ignore */ }
  });
  return server;
}

export function listenLoopback(server, port, name, onListen) {
  server.listen(port, '127.0.0.1', () => {
    onListen?.();
  });
  server.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
      // A healthy same-version instance already holds the port (concurrent
      // launcher race) — exit quietly; ensure-proxy owns stale-instance kills.
      process.stderr.write(`[${name}] port ${port} already bound — assuming healthy instance, exiting\n`);
      process.exit(0);
    }
    process.stderr.write(`[${name}] error: ${err.message}\n`);
    process.exit(1);
  });
  return server;
}
