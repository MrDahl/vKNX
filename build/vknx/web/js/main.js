import { h, icon, setText, setCls } from './util.js';
import { api, conn } from './api.js';
import { S, on, emit, navigate, hooks } from './bus.js';
import { toast } from './dialogs.js';
import { applyTheme, currentTheme, linkSummary, openConnection, openSettings } from './settings.js';
import { createInstallation } from './views/installation.js';
import { createSimulation } from './views/simulation.js';
import { createMonitorView } from './views/monitor.js';

const app = document.getElementById('app');

// ----- shell -----
const logo = `<svg viewBox="0 0 48 48" aria-hidden="true"><rect x="2" y="2" width="44" height="44" rx="11" fill="var(--accent)"/><path d="M13 14l11 20 11-20" fill="none" stroke="var(--accent-ink)" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
const NAV = [['installation', 'Installation', 'devices'], ['simulation', 'Simulation', 'plan'], ['monitor', 'Monitor', 'prog']];
const navBtns = {};
const nav = h('nav.nav', { 'aria-label': 'Workspaces' });
for (const [key, label, ic] of NAV) {
  const count = h('span.count');
  navBtns[key] = { btn: h('button', { type: 'button', onclick: () => navigate(key) }, icon(ic), h('span', label), key === 'installation' ? count : null), count };
  nav.append(navBtns[key].btn);
}
const connBtn = h('button.conn', { type: 'button', onclick: openConnection, 'aria-label': 'Connection status' }, h('span.shape'), h('span.conn-text'));
const themeBtn = h('button.btn.quiet.icon', { type: 'button', onclick: () => { applyTheme(currentTheme() === 'dark' ? 'light' : 'dark', true); drawTheme(); } });
function drawTheme() {
  const dark = currentTheme() === 'dark';
  themeBtn.replaceChildren(icon(dark ? 'sun' : 'moon'));
  themeBtn.setAttribute('aria-label', dark ? 'Switch to the light theme' : 'Switch to the dark theme'); themeBtn.title = themeBtn.getAttribute('aria-label');
}
drawTheme();
const version = h('span.version');
const topbar = h('header.topbar', h('div.brand', { innerHTML: logo }, h('span', 'v', h('i', 'KNX'))), nav,
  h('div.right', connBtn, version, themeBtn, h('button.btn.quiet.icon', { type: 'button', 'aria-label': 'Settings', title: 'Settings', onclick: openSettings }, icon('cog'))));
const banner = h('div.banner', { role: 'status', hidden: true });
const views = { installation: createInstallation(), simulation: createSimulation(), monitor: createMonitorView() };
const main = h('main', ...Object.values(views).map(v => v.el));
for (const [k, v] of Object.entries(views)) v.el.dataset.view = k;
app.append(topbar, banner, main);

function showView() {
  for (const [k, v] of Object.entries(views)) v.el.hidden = k !== S.view;
  for (const [k, b] of Object.entries(navBtns)) { if (k === S.view) b.btn.setAttribute('aria-current', 'page'); else b.btn.removeAttribute('aria-current'); }
  const v = views[S.view]; (v.onShow || v.update)();
}
on('nav', showView);

function header() {
  const s = linkSummary();
  connBtn.className = 'conn ' + s.cls; connBtn.title = s.title || 'Connection status';
  setText(connBtn.querySelector('.conn-text'), s.text);
  setText(version, S.state?.version || '');
  setText(navBtns.installation.count, S.state?.devices.length ?? '');
  setCls(app, 'stale', !conn.ok);
}

// ----- connection loss and recovery -----
let bannerTimer;
on('conn', ev => {
  clearTimeout(bannerTimer);
  if (ev.lost) {
    banner.className = 'banner'; banner.hidden = false;
    banner.replaceChildren(icon('alert'), 'Connection to the simulator lost. Values on screen may be outdated, and actions are not carried out. Trying to reconnect…');
  } else {
    banner.className = 'banner ok'; banner.hidden = false;
    banner.replaceChildren(icon('check'), 'Connection re-established. Values are up to date again.');
    bannerTimer = setTimeout(() => { banner.hidden = true; }, 5000);
    S.since = S.since; // the log is requested from the last known entry, nothing is lost or repeated
  }
  header();
});

// ----- polling -----
function ingest(state) {
  const restarted = S.state && S.state.started !== state.started;
  if (restarted) { S.telegrams = []; S.devlog = []; S.since = 0; S.monCutoff = 0; }
  S.state = state;
  emit('state');
  header();
}
function ingestLog(res) {
  let bus = false;
  for (const e of res.entries) {
    S.since = Math.max(S.since, e.seq);
    if (e.tg) { S.telegrams.push(e); bus = true; } else S.devlog.push(e);
  }
  if (S.telegrams.length > 3000) S.telegrams = S.telegrams.slice(-2500);
  if (S.devlog.length > 1500) S.devlog = S.devlog.slice(-1200);
  if (res.entries.length) emit('telegrams', { bus });
}
async function loadCatalog() {
  if (S.catalog.length) return;
  try { S.catalog = await api.catalog(); S.cat = Object.fromEntries(S.catalog.map(p => [p.key, p])); emit('state'); } catch (e) { /* retried on the next tick */ }
}
let busy = false, shown = false;
async function tick() {
  while (busy) await busy;
  let done; busy = new Promise(r => { done = r; });
  try {
    const st = await api.state();
    if (!S.catalog.length) await loadCatalog(); // before the first render, so icons and titles are right from the start
    ingest(st);
    if (!shown) { shown = true; showView(); }
    ingestLog(await api.log(S.since));
  } catch (e) { header(); }
  finally { busy = false; done(); }
}
hooks.refresh = tick;
(function loop() { tick().finally(() => setTimeout(loop, document.hidden ? 2500 : 600)); })();

showView(); header();
window.vknx = { S, emit, navigate }; // handy for tests and debugging
