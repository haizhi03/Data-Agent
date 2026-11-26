/** Minimal packaged host: static UI, private API proxy, and ownership of the Java pipe. */
import http from 'node:http';
import { spawn } from 'node:child_process';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, readFile, realpath, stat } from 'node:fs/promises';
import { homedir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import readline from 'node:readline';
import { once } from 'node:events';

const bundle = path.dirname(fileURLToPath(import.meta.url));
const data = path.resolve(process.env.DATA_AGENT_DATA_DIRECTORY || path.join(homedir(), '.data-agent', 'local'));
const java = path.join(bundle, 'runtime/java/bin/java');
const workerScript = path.join(bundle, 'worker/src/worker.js');
const prefix = 'DATA_AGENT_HOST_READY ';
let backend, bootstrap, log, stopping = false;
const sockets = new Set();
const server = http.createServer((req, res) => { void handle(req, res).catch(() => {
  if (res.headersSent) res.destroy(); else { res.writeHead(500); res.end('HOST_REQUEST_FAILED'); }
}); });
server.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
const apiRequests = new Set();
let origin;

async function handle(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  // Exact host and origin checks also prevent DNS rebinding and cross-site API requests.
  if (req.headers.host !== new URL(origin).host || (req.headers.origin && req.headers.origin !== origin)
      || req.headers['sec-fetch-site'] === 'cross-site') {
    res.writeHead(403); res.end('HOST_ORIGIN_REJECTED'); return;
  }
  if (!bootstrap || stopping) { res.writeHead(503); res.end('HOST_STARTING'); return; }
  const url = new URL(req.url, origin);
  if (url.pathname.startsWith('/api/')) {
    const headers = { ...req.headers, host: new URL(bootstrap.apiBaseURL).host,
      authorization: `Bearer ${bootstrap.transportToken}`,
      'x-local-runtime-id': req.headers['x-local-runtime-id'] ?? bootstrap.runtimeInstanceId };
    for (const key of ['origin', 'connection', 'proxy-authorization', 'transfer-encoding']) delete headers[key];
    const upstream = http.request(new URL(url.pathname + url.search, bootstrap.apiBaseURL), { method: req.method, headers }, response => {
      res.writeHead(response.statusCode, response.headers);
      response.pipe(res);
    });
    apiRequests.add(upstream);
    upstream.on('close', () => apiRequests.delete(upstream));
    upstream.on('error', () => { if (res.headersSent) res.destroy(); else { res.writeHead(502); res.end('BACKEND_UNAVAILABLE'); } });
    res.on('close', () => upstream.destroy());
    req.pipe(upstream);
    return;
  }
  if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405); res.end(); return; }
  if (url.pathname === '/__data_agent_host.js') {
    // Only the public identity crosses into the browser. The private token stays in this process.
    const publicBootstrap = { ...bootstrap, apiBaseURL: origin + '/api', transportToken: '' };
    res.setHeader('Content-Type', 'text/javascript; charset=utf-8');
    res.end(`window.dataAgentHost={getBootstrap:async()=>(${JSON.stringify(publicBootstrap)})};`);
    return;
  }
  const web = path.join(bundle, 'web');
  let file = path.resolve(web, '.' + decodeURIComponent(url.pathname));
  if (!file.startsWith(web + path.sep) && file !== web) { res.writeHead(404); res.end(); return; }
  try { if (!(await stat(file)).isFile()) file = path.join(web, 'index.html'); }
  catch { if (path.extname(file)) { res.writeHead(404); res.end(); return; } file = path.join(web, 'index.html'); }
  if (!(await realpath(file)).startsWith(await realpath(web) + path.sep)) { res.writeHead(404); res.end(); return; }
  const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
    '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.ttf': 'font/ttf' };
  res.setHeader('Content-Type', (types[path.extname(file)] || 'application/octet-stream') + '; charset=utf-8');
  if (req.method === 'HEAD') { res.end(); return; }
  if (file === path.join(web, 'index.html')) {
    res.end((await readFile(file, 'utf8')).replace('</head>', '<script src="/__data_agent_host.js"></script></head>'));
  } else createReadStream(file).on('error', () => res.destroy()).pipe(res);
}

async function stop(code) {
  if (stopping) return;
  stopping = true;
  bootstrap = undefined;
  server.close();
  for (const socket of sockets) socket.destroy();
  for (const request of apiRequests) request.destroy();
  if (backend?.pid && backend.exitCode === null && backend.signalCode === null) {
    const exited = once(backend, 'exit');
    backend.stdin.end();
    const timer = setTimeout(() => backend.kill('SIGKILL'), 15000);
    await exited;
    clearTimeout(timer);
  }
  log?.end();
  process.exitCode = code;
}
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { void stop(0); });

async function probeWorker() {
  const worker = spawn(process.execPath, [workerScript], { cwd: data, stdio: ['pipe', 'pipe', 'pipe'] });
  let hello;
  const lines = readline.createInterface({ input: worker.stdout });
  lines.on('line', line => { try { hello ??= JSON.parse(line); } catch { /* Rejected below. */ } worker.stdin.end(); });
  worker.stderr.on('data', chunk => log.write(chunk));
  const timer = setTimeout(() => worker.kill('SIGKILL'), 10000);
  try {
    const [code] = await once(worker, 'exit');
    if (code !== 0 || hello?.type !== 'hello' || hello.protocol !== 1
        || hello.piAgentCore !== '1.5.0' || hello.piAi !== '1.5.0') throw new Error('WORKER_HANDSHAKE_FAILED');
  } finally { clearTimeout(timer); lines.close(); }
}

async function start() {
  await mkdir(path.join(data, 'logs'), { recursive: true, mode: 0o700 });
  log = createWriteStream(path.join(data, 'logs/desktop-host.log'), { flags: 'a', mode: 0o600 });
  log.on('error', () => { process.stderr.write('HOST_LOG_UNAVAILABLE\n'); void stop(1); });
  await probeWorker();
  if (stopping) return;
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  origin = `http://127.0.0.1:${server.address().port}`;
  backend = spawn(java, ['-jar', path.join(bundle, 'server.jar'), '--server.port=0', '--server.address=127.0.0.1',
    '--spring.main.banner-mode=off', '--app.local.host-stdio=true', '--app.runtime.mode=local',
    `--app.local.data-directory=${data}`, `--app.local.allowed-origins=${origin}`,
    '--app.local.pi.enabled=true', `--app.local.pi.node-binary=${process.execPath}`, `--app.local.pi.worker-script=${workerScript}`],
    { cwd: data, stdio: ['pipe', 'pipe', 'pipe'] });
  backend.stderr.on('data', chunk => log.write(chunk));
  backend.stdin.on('error', () => {});
  const timeout = setTimeout(() => { process.stderr.write('BACKEND_STARTUP_TIMEOUT\n'); void stop(1); }, 45000);
  const lines = readline.createInterface({ input: backend.stdout });
  lines.on('line', line => {
    if (!line.startsWith(prefix)) { log.write(line + '\n'); return; }
    if (stopping || bootstrap) return;
    try {
      const message = JSON.parse(line.slice(prefix.length));
      const endpoint = new URL(message.apiBaseURL);
      if (message.contractVersion !== 1 || endpoint.protocol !== 'http:' || endpoint.hostname !== '127.0.0.1'
          || endpoint.pathname !== '/api' || !endpoint.port || !/^[A-Za-z0-9_-]{43}$/.test(message.transportToken)
          || !/^[0-9a-f-]{36}$/.test(message.runtimeInstanceId)) throw new Error();
      bootstrap = message;
      clearTimeout(timeout);
      process.stdout.write(JSON.stringify({ type: 'ready', url: origin, hostPid: process.pid, backendPid: backend.pid }) + '\n');
    } catch { process.stderr.write('BACKEND_BOOTSTRAP_INVALID\n'); void stop(1); }
  });
  backend.once('error', () => { clearTimeout(timeout); process.stderr.write('BACKEND_START_FAILED\n'); void stop(1); });
  backend.once('exit', () => { clearTimeout(timeout); lines.close(); if (!stopping) { process.stderr.write('BACKEND_EXITED\n'); void stop(1); } });
}
start().catch(() => { process.stderr.write('HOST_START_FAILED\n'); void stop(1); });
