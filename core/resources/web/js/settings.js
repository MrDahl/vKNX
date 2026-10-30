import { h, icon, setText, ago, clock } from './util.js';
import { api, conn } from './api.js';
import { S, on } from './bus.js';
import { modal, toast } from './dialogs.js';

export function applyTheme(t, persist) {
  document.documentElement.dataset.theme = t;
  if (persist) { try { localStorage.setItem('vknx-theme', t); } catch (e) { /* ignore */ } }
}
export const currentTheme = () => document.documentElement.dataset.theme || 'light';

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

export function openSettings() {
  const upd = h('div'); const lineMsg = h('div.hint'); 
  const line = h('input.input', { type: 'text', size: 6, value: S.state?.line || '1.1', 'aria-label': 'Line', style: 'width:6rem' });
  const themeSeg = h('div.segmented', { role: 'group', 'aria-label': 'Theme' });
  const mk = (t, label, ic) => { const b = h('button', { type: 'button', 'aria-pressed': String(currentTheme() === t), onclick: () => { applyTheme(t, true); for (const x of themeSeg.children) x.setAttribute('aria-pressed', String(x === b)); } }, icon(ic), label); return b; };
  themeSeg.append(mk('light', 'E-ink (paper)', 'sun'), mk('dark', 'E-ink (dark)', 'moon'));
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
  modal({
    title: 'Settings', wide: true,
    body: [
      sec('Appearance', themeSeg),
      sec('About', h('div', 'Version ', h('b', S.state?.version || '?')), h('p.hint', { style: 'margin:.3rem 0 .6rem' }, 'vKNX is an open-source teaching simulator. The generated product files are not made or endorsed by the KNX Association.'), check, upd),
      sec('KNXnet/IP interface', h('div', 'Interface address: ', h('b.mono', S.state?.iface || '')), h('p.hint', 'ETS connects to the interface "vKNX". The line decides the physical addresses of its tunnels (line.241–244). Changing it restarts vKNX.'),
        h('div', { style: 'display:flex;gap:.5rem;align-items:center' }, line, h('button.btn', { onclick: saveLine }, icon('restart'), 'Save and restart')), lineMsg),
    ],
    buttons: close => [h('button.btn.primary', { onclick: close }, 'Close')],
  });
}
