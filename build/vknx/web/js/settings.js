import { h, icon, setText, ago, clock } from './util.js';
import { api, conn } from './api.js';
import { S, on } from './bus.js';
import { modal, toast, confirmDialog } from './dialogs.js';

export function applyTheme(t, persist) {
  document.documentElement.dataset.theme = t;
  if (persist) { try { localStorage.setItem('vknx-theme', t); } catch (e) { /* ignore */ } }
}
export const currentTheme = () => document.documentElement.dataset.theme || 'light';
export function applySize(v, persist) {
  if (v === 'large') document.documentElement.dataset.size = 'large'; else delete document.documentElement.dataset.size;
  if (persist) { try { localStorage.setItem('vknx-size', v); } catch (e) { /* ignore */ } }
}
export const currentSize = () => document.documentElement.dataset.size === 'large' ? 'large' : 'normal';

/** What can be said about the connection, and how sure we are. Used by the header indicator and the details dialog. */
export function linkSummary() {
  const link = S.state?.link;
  if (!conn.ok) return { cls: 'bad', text: 'Connection lost', title: 'The simulator is not responding. Values on screen may be outdated.' };
  if (!link) return { cls: '', text: 'Connecting…', title: '' };
  if (link.server !== 'none' && link.server !== 'Running') return { cls: 'warn', text: 'KNX interface ' + link.server.toLowerCase(), title: 'The KNXnet/IP server is not running.' };
  if (link.tunnels.length) return { cls: 'ok', text: 'ETS / IP client connected', title: `${link.tunnels.length} KNXnet/IP connection(s) reported by the server.` };
  return { cls: '', text: 'Interface ready · no client', title: 'The KNXnet/IP server is running. No ETS or other IP client is connected right now.' };
}

export function openConnection() {
  const rows = () => {
    const link = S.state?.link;
    const tun = link?.tunnels || [];
    return [
      ['Simulator (this window)', conn.ok ? 'Responding' : `Not responding since ${clock(conn.lostAt)}`, conn.ok ? 'ok' : 'danger',
        'Whether this page can reach the vKNX program on your computer.'],
      ['KNX interface (KNXnet/IP server)', !link ? '–' : link.server === 'none' ? 'Not started in this mode' : link.server === 'Running' ? `Running on port ${link.port}` : link.server,
        link?.server === 'Running' ? 'ok' : 'warn', `Configured interface address: ${S.state?.iface || '?'} – a setting, not proof of a connection.`],
      ['ETS / IP client', !link ? '–' : tun.length ? `${tun.length} connection${tun.length === 1 ? '' : 's'}` : 'None connected', tun.length ? 'ok' : '',
        tun.length ? tun.map(t => `${t.type} as ${t.address}, since ${t.since ? clock(t.since) : '?'}`).join('; ') : 'No tunnel or management connection is open.'],
      ['Last frame from an IP client', link?.lastIpFrame ? ago(link.lastIpFrame) : 'None seen', '', 'Frames that entered the line through the KNXnet/IP interface (for example from ETS).'],
    ];
  };
  const box = h('div');
  const draw = () => box.replaceChildren(h('table.tbl', h('tbody', rows().map(([a, b, cls, c]) => h('tr', h('td', { style: 'width:34%' }, h('b', a)), h('td', h(`span.chip${cls ? '.' + cls : ''}`, b)), h('td.hint', c))))),
    h('p.hint', { style: 'margin-bottom:0' }, 'vKNX only reports what the server can verify. An ETS download or group monitor may still fail for reasons outside this list.'));
  draw();
  const off = on('state', draw);
  const dlg = modal({ title: 'Connection status', body: box, buttons: close => [h('button.btn.primary', { onclick: close }, 'Close')] });
  dlg.addEventListener('close', off);
}

const fmtBytes = b => b < 1024 ? `${b} B` : b < 1048576 ? `${(b / 1024).toFixed(0)} KB` : b < 1073741824 ? `${(b / 1048576).toFixed(1)} MB` : `${(b / 1073741824).toFixed(2)} GB`;

/** "Data and cleanup": what vKNX keeps on disk, with buttons to remove the parts that are not needed. */
function dataSection() {
  const box = h('div');
  const info = {
    logs: ['Logs', 'vknx.log and console.log. Only useful when something went wrong. Each is limited to 10 MB (plus one older copy), so they cannot grow without end.', 'Delete logs'],
    backups: ['Old device backups', 'vKNX keeps the old state of a device each time ETS resets it (factory reset, "deactivate secure commissioning"). Nothing in vKNX uses them. Only the newest one per device is kept automatically.', 'Delete backups'],
    plan: ['Floor plan and icons', 'The plan image, where the devices are placed on it and the icons you chose.', 'Remove floor plan and icons'],
    state: ['Devices (addresses, downloads, keys)', 'What ETS has programmed into the devices. This is the installation itself.', null],
  };
  async function load() {
    let items;
    try { items = (await api.dataInfo()).items; } catch (e) { box.replaceChildren(h('span.error-text', 'Could not read the data folder: ' + e.message)); return; }
    const row = it => {
      const [title, text, label] = info[it.id];
      const done = async () => { try { const r = await api.dataClear(it.id); toast(`Freed ${fmtBytes(r.freed)}.`); if (it.id === 'plan') location.reload(); else load(); } catch (e) { toast('Could not clear: ' + e.message); } };
      return h('tr', h('td', { style: 'width:30%' }, h('b', title), h('div.hint', `${it.files} file${it.files === 1 ? '' : 's'} · ${fmtBytes(it.bytes)}`)),
        h('td.hint', text),
        h('td', { style: 'white-space:nowrap' }, label ? h('button.btn', { disabled: it.files === 0, onclick: async () => {
          if (it.id === 'plan' && !await confirmDialog({ title: 'Remove the floor plan and icons?', message: 'The plan image, the placement of the devices on it and the icons you chose are deleted. The devices themselves are not changed.', confirmLabel: 'Remove', danger: true })) return;
          done();
        } }, label) : null));
    };
    box.replaceChildren(h('table.tbl', h('tbody', items.map(row))),
      h('div', { style: 'margin-top:.8rem' }, h('button.btn.danger', { onclick: newInstallation }, icon('restart'), 'Start a new installation…')),
      h('p.hint', 'Resets every device to factory state and clears the logs and backups. The devices stay, with their names and lines. Use it when you start a new ETS project.'));
  }
  async function newInstallation() {
    const plan = h('input', { type: 'checkbox', id: 'ni-plan' });
    const msg = h('div', h('p', 'This resets every virtual device to factory state:'),
      h('ul', h('li', 'addresses and downloaded applications are gone (download them again from ETS),'),
        h('li', 'secure devices get a new factory key (FDSK) and a new certificate, so they must be added again in ETS,'),
        h('li', 'logs and device backups are deleted.')),
      h('label', { style: 'display:flex;gap:.5rem;align-items:center' }, plan, 'Also remove the floor plan and icons'));
    if (!await confirmDialog({ title: 'Start a new installation?', message: msg, confirmLabel: 'Reset all devices', danger: true })) return;
    try { await api.dataClear('installation', plan.checked ? { plan: '1' } : {}); toast('New installation ready. All devices are in factory state.'); setTimeout(() => location.reload(), 600); }
    catch (e) { toast('Could not start a new installation: ' + e.message); }
  }
  load();
  return h('section.section', { style: 'border:0;margin-bottom:1.1rem' }, h('h3', { style: 'margin-bottom:.5rem' }, 'Data and cleanup'), box);
}

export function openSettings() {
  const upd = h('div'); const lineMsg = h('div.hint'); 
  const line = h('input.input', { type: 'text', size: 6, value: S.state?.line || '1.1', 'aria-label': 'Line', style: 'width:6rem' });
  const themeSeg = h('div.segmented', { role: 'group', 'aria-label': 'Theme' });
  const mk = (t, label, ic) => { const b = h('button', { type: 'button', 'aria-pressed': String(currentTheme() === t), onclick: () => { applyTheme(t, true); for (const x of themeSeg.children) x.setAttribute('aria-pressed', String(x === b)); } }, icon(ic), label); return b; };
  themeSeg.append(mk('light', 'Light', 'sun'), mk('dark', 'Dark', 'moon'), mk('paper', 'Paper (e-ink)', 'edit'));
  const sizeSeg = h('div.segmented', { role: 'group', 'aria-label': 'Size' });
  for (const [v, label] of [['normal', 'Normal'], ['large', 'Large (projector)']]) {
    const b = h('button', { type: 'button', 'aria-pressed': String(currentSize() === v), onclick: () => { applySize(v, true); for (const x of sizeSeg.children) x.setAttribute('aria-pressed', String(x === b)); } }, label);
    sizeSeg.append(b);
  }
  const check = h('button.btn.primary', { onclick: checkUpdates }, icon('update'), 'Check for updates');

  async function checkUpdates() {
    upd.replaceChildren(h('span.hint', 'Checking…')); check.disabled = true;
    try {
      const u = await api.updateCheck();
      if (!u.newer) { upd.replaceChildren(h('span.chip.ok', icon('check'), `vKNX is up to date (${u.latest || u.current})`)); return; }
      const prog = h('div');
      upd.replaceChildren(h('div', h('b', 'New version ' + u.latest)), h('div.hint', 'Release notes'), h('pre', { style: 'white-space:pre-wrap;max-height:10rem;overflow:auto;background:var(--surface-2);padding:.6rem;border-radius:6px;margin:.3rem 0' }, u.notes || '–'),
        h('div', { style: 'display:flex;gap:.6rem;align-items:center;flex-wrap:wrap' },
          u.canInstall ? h('button.btn.primary', { onclick: () => install(u.asset, prog) }, icon('download'), 'Download and install') : h('span.hint', 'This copy was not started from the installed program, so it cannot update itself.'),
          h('a', { href: u.page, target: '_blank', rel: 'noopener' }, 'Open the release page')), prog);
    } catch (e) { upd.replaceChildren(h('span.error-text', `The update check failed: ${e.message}`)); }
    finally { check.disabled = false; }
  }
  async function install(asset, box) {
    box.replaceChildren(h('span.hint', 'Downloading…'));
    try { await api.updateInstall(asset); } catch (e) { box.replaceChildren(h('span.error-text', e.message)); return; }
    const iv = setInterval(async () => {
      try {
        const s = await api.updateStatus();
        if (s.state === 'downloading') box.replaceChildren(h('span.hint', 'Downloading ' + (s.total ? Math.round(s.bytes * 100 / s.total) + ' %' : '')), h('div.meter', h('i', { style: `width:${s.total ? s.bytes * 100 / s.total : 5}%` })));
        else if (s.state === 'installing') box.replaceChildren(h('span.hint', 'Installing… vKNX will restart.'));
        else if (s.state === 'error') { clearInterval(iv); box.replaceChildren(h('span.error-text', s.error)); }
      } catch (e) { box.replaceChildren(h('span.hint', 'Installing… vKNX will restart.')); }
    }, 500);
  }
  async function saveLine() {
    const v = line.value.trim();
    if (!/^\d{1,2}\.\d{1,2}$/.test(v)) { setText(lineMsg, 'Enter the line as area.line, for example 1.1.'); lineMsg.className = 'error-text'; return; }
    try { await api.saveLine(v); } catch (e) { setText(lineMsg, `The line could not be saved: ${e.message}`); lineMsg.className = 'error-text'; return; }
    try { await api.restart(); } catch (e) { setText(lineMsg, 'Saved. Restart vKNX manually to apply the new line.'); lineMsg.className = 'hint'; return; }
    setText(lineMsg, 'Restarting…'); lineMsg.className = 'hint';
    let down = false; const t0 = Date.now();
    const iv = setInterval(async () => { try { await fetch('/api/state', { cache: 'no-store' }); if (down || Date.now() - t0 > 9000) { clearInterval(iv); location.reload(); } } catch (e) { down = true; } }, 600);
  }
  const sec = (title, ...c) => h('section.section', { style: 'border:0;margin-bottom:1.1rem' }, h('h3', { style: 'margin-bottom:.5rem' }, title), ...c);
  const routing = h('div', h('label', { style: 'display:flex;gap:.5rem;align-items:center' },
    h('input', { type: 'checkbox', checked: S.state?.routing !== false, onchange: async e => { try { await api.saveRouting(e.target.checked); toast(e.target.checked ? 'Telegrams now cross lines only through couplers.' : 'All devices hear everything again (one flat line).'); } catch (err) { e.target.checked = !e.target.checked; toast('Could not change the setting: ' + err.message); } } }),
    'Route telegrams between lines through couplers'),
    h('p.hint', 'On: a device on another line only hears a telegram if a coupler with the right address exists, is downloaded, and lets it pass (filter table, hop count). Off: every device hears everything, couplers have no effect.'));
  const TABS = [
    ['appearance', 'Appearance', () => [sec('Appearance', themeSeg, h('div', { style: 'margin-top:.6rem' }, sizeSeg), h('p.hint', 'Light is bright and high contrast for projectors. Large makes text and buttons bigger. Paper is a flat, animation-free look for e-ink screens.'))]],
    ['connection', 'Connection', () => [
      sec('KNXnet/IP interface', h('div', 'Interface address: ', h('b.mono', S.state?.iface || '')), h('p.hint', 'ETS connects to the interface "vKNX". The line decides the physical addresses of its tunnels (line.241–244). Changing it restarts vKNX.'),
        h('div', { style: 'display:flex;gap:.5rem;align-items:center' }, line, h('button.btn', { onclick: saveLine }, icon('restart'), 'Save and restart')), lineMsg),
      sec('Status', h('p.hint', { style: 'margin-top:0' }, 'Whether ETS or another IP client is connected, and when the last frame arrived.'), h('button.btn', { onclick: openConnection }, icon('info'), 'Show connection status'))]],
    ['simulation', 'Simulation', () => [sec('Lines and couplers', routing)]],
    ['maintenance', 'Maintenance', () => [dataSection()]],
    ['about', 'About', () => [sec('About', h('div', 'Version ', h('b', S.state?.version || '?')), h('p', { style: 'margin:.3rem 0' }, 'Developed by ', h('b', 'Marc Sonne Dahl'), ' · ', h('a', { href: 'https://www.marcdahl.dk', target: '_blank', rel: 'noopener' }, 'www.marcdahl.dk')), h('p.hint', { style: 'margin:.3rem 0' }, 'Open source under the Apache License 2.0 · ', h('a', { href: 'https://github.com/MrDahl/vKNX', target: '_blank', rel: 'noopener' }, 'github.com/MrDahl/vKNX')), h('p.hint', { style: 'margin:.3rem 0 .6rem' }, 'vKNX is an open-source teaching simulator. The generated product files are not made or endorsed by the KNX Association.'), check, upd)]],
  ];
  const bar = h('div.tabs', { role: 'tablist', 'aria-label': 'Settings sections', style: 'padding:0;margin:-.3rem 0 .9rem' });
  const pane = h('div', { role: 'tabpanel' });
  const btns = {};
  const built = {};
  const show = k => {
    for (const [key, b] of Object.entries(btns)) { b.setAttribute('aria-selected', String(key === k)); b.tabIndex = key === k ? 0 : -1; }
    pane.replaceChildren(...(built[k] ||= TABS.find(t => t[0] === k)[2]()));
    try { sessionStorage.setItem('vknx-settings-tab', k); } catch (e) { /* ignore */ }
  };
  for (const [k, t] of TABS) bar.append(btns[k] = h('button.tab', { type: 'button', role: 'tab', onclick: () => show(k) }, t));
  let first = 'appearance'; try { first = sessionStorage.getItem('vknx-settings-tab') || first; } catch (e) { /* ignore */ }
  show(TABS.some(t => t[0] === first) ? first : 'appearance');
  modal({
    title: 'Settings', wide: true,
    body: [bar, pane],
    buttons: close => [h('button.btn.primary', { onclick: close }, 'Close')],
  });
}
