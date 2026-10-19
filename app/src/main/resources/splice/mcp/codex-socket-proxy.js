// The shared Codex control endpoint speaks WebSocket, not the stdio protocol's raw newline stream.
// Use the same ws dependency as Temple's native observer. The private API stays on its owned Unix socket.
import { lstatSync, realpathSync } from 'node:fs';
import { createRequire } from 'node:module';

const MAX_BYTES = 8_388_608;
const HANDSHAKE_MS = 1500;
const [control, templeScript] = process.argv.slice(2);
let socket;

function fail() {
  process.stderr.write('Native Codex WebSocket transport failed.\n');
  socket?.terminate();
  process.exit(1);
}

try {
  const account = process.getuid?.();
  const endpoint = realpathSync(control);
  const target = lstatSync(endpoint);
  if (lstatSync(control).uid !== account || target.uid !== account || !target.isSocket() || endpoint.includes(':')) {
    fail();
  }
  const WebSocket = createRequire(templeScript)('ws');
  socket = new WebSocket(`ws+unix:${endpoint}:/`, {
    followRedirects: false,
    handshakeTimeout: HANDSHAKE_MS,
    maxPayload: MAX_BYTES,
  });
  process.stdin.pause();
  process.stdin.setEncoding('utf8');
  let pending = '';
  socket.once('open', () => process.stdin.resume());
  socket.on('message', (data, binary) => {
    if (binary) fail();
    process.stdout.write(data.toString('utf8') + '\n');
  });
  socket.on('error', fail);
  socket.on('close', () => {
    process.stdin.destroy();
    process.exitCode = 0;
  });
  process.stdin.on('data', chunk => {
    pending += chunk;
    if (Buffer.byteLength(pending) > MAX_BYTES) fail();
    let end;
    while ((end = pending.indexOf('\n')) >= 0) {
      const line = pending.slice(0, end);
      pending = pending.slice(end + 1);
      if (line.trim()) socket.send(line);
    }
  });
  process.stdin.on('end', () => socket.close());
  process.stdin.on('error', fail);
} catch {
  fail();
}
