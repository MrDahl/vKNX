import { emit } from './bus.js';

/** Connection health as seen from the browser: does the simulator API answer? (Not the same as an ETS connection.) */
export const conn = { ok: true, failures: 0, lostAt: 0, lastOk: Date.now() };

export class ApiError extends Error {}

async function req(url, { method = 'GET', body, timeout = 5000, raw = false } = {}) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeout);
  let res;
  try {
    res = await fetch(url, { method, body, signal: ctl.signal, cache: 'no-store' });
  } catch (e) {
    markFail();
    throw new ApiError('The simulator is not responding. Is vKNX still running?');
  } finally {
    clearTimeout(timer);
  }
  markOk();
  if (!res.ok) {
    let msg = '';
    try { msg = (await res.text()).trim(); } catch (e) { /* ignore */ }
    throw new ApiError(msg || `The simulator answered with an error (${res.status}).`);
  }
  return raw ? res : (res.headers.get('content-type') || '').includes('json') ? res.json() : res.text();
}
function markOk() {
  conn.lastOk = Date.now();
  if (!conn.ok) { conn.ok = true; conn.failures = 0; emit('conn', { restored: true }); }
}
function markFail() {
  conn.failures++;
  if (conn.ok && conn.failures >= 2) { conn.ok = false; conn.lostAt = Date.now(); emit('conn', { lost: true }); }
}

const q = o => Object.entries(o).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');

export const api = {
  state: () => req('/api/state'),
  log: since => req('/api/log?since=' + since),
  catalog: () => req('/api/catalog'),
  act: (dev, a, v = 0) => req('/api/act?' + q({ dev, a, v }), { method: 'POST' }),
  prog: dev => req('/api/prog?' + q({ dev }), { method: 'POST' }),
  add: (product, name) => req('/api/add?' + q({ product, name }), { method: 'POST' }),
  remove: dev => req('/api/remove?' + q({ dev }), { method: 'POST' }),
  layout: () => req('/api/layout'),
  saveLayout: obj => req('/api/layout', { method: 'POST', body: JSON.stringify(obj) }),
  planHead: async () => { try { const r = await fetch('/api/plan?t=' + Date.now(), { method: 'GET', cache: 'no-store' }); return r.ok; } catch (e) { return false; } },
  planUpload: (ext, buf) => req('/api/plan?' + q({ ext }), { method: 'POST', body: buf, timeout: 30000 }),
  planRemove: () => req('/api/plan?remove=1', { method: 'POST' }),
  updateCheck: () => req('/api/update/check', { timeout: 20000 }),
  updateInstall: asset => req('/api/update/install?' + q({ asset }), { method: 'POST' }),
  updateStatus: () => req('/api/update/status'),
  saveLine: v => req('/api/settings?' + q({ line: v }), { method: 'POST' }),
  saveRouting: v => req('/api/settings?' + q({ routing: String(v) }), { method: 'POST' }),
  restart: () => req('/api/restart', { method: 'POST' }),
};
