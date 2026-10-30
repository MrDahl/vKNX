import { h, icon, setText, setCls, syncKeyed, clock, naturalCmp, flagLetters } from '../util.js';
import { S, on, emit, setFilter, resetFilter, selectGa, device } from '../bus.js';
import { gaIndex } from '../devinfo.js';
import { toast } from '../dialogs.js';

const MAX_ROWS = 400;
const SERVICES = [['', 'All services'], ['write', 'Group write'], ['read', 'Group read'], ['response', 'Group response'], ['other', 'Other services']];

export const svcKind = s => /GroupValue.*Write/i.test(s) ? 'write' : /GroupValue.*Read/i.test(s) ? 'read' : /GroupValue.*Response/i.test(s) ? 'response' : 'other';
const svcLabel = s => s.replace('A_', '').replace(/GroupValue/, 'Group ').replace(/_/g, ' ').trim() || s;

/** Raw data bytes of a group value telegram, from the TPDU (display only - never used to guess a type). */
export function rawData(tg) {
  const b = tg.tpdu.match(/../g) || [];
  if (b.length < 2) return '';
  if (b.length === 2) return '0x' + (parseInt(b[1], 16) & 0x3f).toString(16).padStart(2, '0') + ' (6-bit field)';
  return '0x' + b.slice(2).join(' ');
}

function nameOfAddr(addr) { return S.state?.devices.find(d => !d.unassigned && d.address === addr)?.name || ''; }

/** Is the entry related to the device: sent by it, addressed to it, or addressed to a group address configured on it? */
function relatedToDevice(e, name) {
  const d = device(name); if (!d) return false;
  const tg = e.tg; if (!tg) return false;
  if (!d.unassigned && tg.src === d.address) return true;
  if (tg.group) return (d.assoc || []).some(a => a.ga === tg.dst);
  return !d.unassigned && tg.dst === d.address;
}

export function filtered() {
  const f = S.filter, q = f.text.trim().toLowerCase();
  const cut = S.monCutoff || 0;
  return S.telegrams.filter(e => {
    if (e.seq <= cut) return false;
    const tg = e.tg;
    if (f.origin && tg.origin !== f.origin) return false;
    if (f.service && svcKind(tg.svc) !== f.service) return false;
    if (f.ga && !(tg.group && tg.dst === f.ga)) return false;
    if (f.device && !relatedToDevice(e, f.device)) return false;
    if (q && !`${tg.src} ${nameOfAddr(tg.src)} ${tg.dst} ${tg.svc} ${tg.value || ''} ${tg.tpdu}`.toLowerCase().includes(q)) return false;
    return true;
  });
}
const activeFilters = () => Object.entries(S.filter).filter(([, v]) => v);

/** Telegram monitor (compact in the simulation dock, full in the Monitor workspace). */
export function createMonitor({ compact }) {
  const st = { paused: false, pausedAt: 0, follow: true, tab: 'telegrams', sel: null, sig: '' };
  const root = h('div.mon');

  const tabs = h('div.segmented', { role: 'group', 'aria-label': 'Monitor content' });
  const bTg = h('button', { type: 'button', onclick: () => setTab('telegrams') }, 'Bus telegrams');
  const bLog = h('button', { type: 'button', onclick: () => setTab('devlog'), title: 'Explanations written by the virtual devices - not telegrams on the bus' }, 'Device log');
  tabs.append(bTg, bLog);
  const bPause = h('button.btn', { type: 'button', onclick: togglePause }, icon('pause'), h('span', 'Pause'));
  const newBadge = h('span.chip.info', { hidden: true });
  const bFollow = h('button.btn', { type: 'button', 'aria-pressed': 'true', title: 'Keep the newest telegram in view', onclick: () => { st.follow = !st.follow; if (st.follow) scrollEnd(); refreshTools(); } }, icon('down'), h('span', 'Follow live'));
  const search = h('input.input', { type: 'search', placeholder: 'Search', 'aria-label': 'Search telegrams', style: 'width:11rem' });
  search.addEventListener('input', () => setFilter({ text: search.value }));
  const selDev = h('select.select', { 'aria-label': 'Filter by device', title: 'Telegrams sent by the device, or addressed to one of its group addresses' });
  selDev.addEventListener('change', () => setFilter({ device: selDev.value }));
  const selSvc = h('select.select', { 'aria-label': 'Filter by telegram type' }, SERVICES.map(([v, t]) => h('option', { value: v }, t)));
  selSvc.addEventListener('change', () => setFilter({ service: selSvc.value }));
  const selOrg = h('select.select', { 'aria-label': 'Filter by origin' },
    [['', 'All origins'], ['ip', 'From ETS / IP clients'], ['device', 'From virtual devices']].map(([v, t]) => h('option', { value: v }, t)));
  selOrg.addEventListener('change', () => setFilter({ origin: selOrg.value }));
  const bReset = h('button.btn', { type: 'button', onclick: resetFilter }, icon('restart'), h('span', 'Reset filters'));
  const bClear = h('button.btn', { type: 'button', title: 'Empties this list only. The simulation keeps running and the log file vknx.log is not changed.', onclick: clearView }, icon('trash'), h('span', 'Clear view'));
  const tools = h('div.mon-tools', tabs, bPause, newBadge, bFollow, h('span.grow'), search, selDev, selSvc, selOrg, bReset, bClear);
  const chips = h('div.mon-filters');
  const thead = h('thead', h('tr', ['Time', 'Source', 'Destination', 'Service', 'Value'].map(t => h('th', t))));
  const tbody = h('tbody');
  const table = h('table.mon-table', thead, tbody);
  const logHead = h('thead', h('tr', ['Time', 'Device', 'Type', 'Explanation'].map(t => h('th', t))));
  const logBody = h('tbody');
  const logTable = h('table.mon-table', logHead, logBody);
  const emptyMsg = h('div.empty', icon('devices'));
  const scroll = h('div.mon-scroll', table, logTable, emptyMsg);
  const jump = h('button.btn.primary.mon-new', { hidden: true, onclick: () => { st.follow = true; scrollEnd(); refreshTools(); } }, icon('down'), 'Jump to live');
  scroll.append(jump);
  const detail = h('div.mon-detail', { hidden: true });
  root.append(tools, chips, scroll, detail);

  function setTab(t) { st.tab = t; st.sig = ''; update(); }
  function togglePause() {
    st.paused = !st.paused;
    st.pausedAt = st.paused ? (S.telegrams.at(-1)?.seq || 0) : 0;
    if (!st.paused) { st.follow = true; }
    st.sig = ''; update();
  }
  function clearView() {
    S.monCutoff = Math.max(S.telegrams.at(-1)?.seq || 0, S.devlog.at(-1)?.seq || 0);
    st.sel = null; emit('filter');
    toast('The monitor view was cleared. The simulation keeps running and the log file vknx.log is unchanged.', 'info');
  }
  function scrollEnd() { scroll.scrollTop = scroll.scrollHeight; }
  scroll.addEventListener('scroll', () => {
    const atEnd = scroll.scrollTop + scroll.clientHeight >= scroll.scrollHeight - 24;
    if (!atEnd && st.follow && scroll.dataset.auto !== '1') { st.follow = false; refreshTools(); }
    if (atEnd && !st.follow && !st.paused) { st.follow = true; refreshTools(); }
  });
  function refreshTools() {
    bFollow.setAttribute('aria-pressed', String(st.follow)); setCls(bFollow, 'on', st.follow);
    jump.hidden = st.follow || st.tab !== 'telegrams';
    if (bPause.dataset.p !== String(st.paused)) {
      bPause.dataset.p = String(st.paused);
      bPause.replaceChildren(icon(st.paused ? 'play' : 'pause'), h('span', st.paused ? 'Resume live' : 'Pause'));
    }
    setCls(bPause, 'primary', st.paused);
  }

  function rowFor(e) {
    const tg = e.tg;
    const tr = h('tr', { 'aria-selected': 'false', tabindex: 0, onclick: () => select(e.seq), onkeydown: ev => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); select(e.seq); } } });
    const sn = nameOfAddr(tg.src);
    let value;
    if (tg.value) value = `${tg.value}`;
    else if (tg.group && /Write|Response/.test(tg.svc)) value = `Unknown DPT · ${rawData(tg)}`;
    else value = '–';
    tr.append(h('td', e.t), h('td', h('span.tag' + (tg.origin === 'ip' ? '.ip' : ''), { title: tg.origin === 'ip' ? 'Entered through the KNXnet/IP interface (ETS or another IP client)' : 'Sent by a virtual device' }, tg.origin === 'ip' ? 'IP' : 'DEV'), ' ', tg.src, sn ? h('span.mute', ' ' + sn) : null),
      h('td', tg.dst), h('td', svcLabel(tg.svc)), h('td.value-col', { title: value }, value));
    return tr;
  }
  function select(seq) { st.sel = st.sel === seq ? null : seq; syncSel(); }
  function syncSel() {
    for (const tr of tbody.children) tr.setAttribute('aria-selected', String(+tr.dataset.key === st.sel));
    const e = S.telegrams.find(x => x.seq === st.sel);
    detail.hidden = !e;
    if (!e) return;
    const tg = e.tg, sn = nameOfAddr(tg.src);
    const members = tg.group ? (gaIndex().get(tg.dst) || []) : [];
    const dpts = [...new Set(members.map(m => m.dpt).filter(Boolean))];
    detail.replaceChildren(
      h('dl.kv',
        h('dt', 'Time'), h('dd', e.t),
        h('dt', 'Origin'), h('dd', tg.origin === 'ip' ? 'Entered through the KNXnet/IP interface (ETS or another IP client)' : 'Sent by a virtual device'),
        h('dt', 'Source'), h('dd', tg.src + (sn ? ' · ' + sn : '')),
        h('dt', 'Destination'), h('dd', tg.dst + (tg.group ? ' (group address)' : ' (device)')),
        h('dt', 'Service'), h('dd', tg.svc),
        h('dt', 'Priority / hops'), h('dd', `${tg.prio} / ${tg.hops}`),
        h('dt', 'Data type'), h('dd', tg.dpt ? `DPT ${tg.dpt} (from the product definition of the devices using this address)` : tg.group ? (dpts.length > 1 ? `Unknown DPT – the devices disagree (${dpts.join(', ')})` : 'Unknown DPT – no device has this address configured with a type') : 'Not a group value telegram'),
        h('dt', 'Decoded value'), h('dd', tg.value || '–'),
        h('dt', 'Raw data'), h('dd.mono', tg.group ? rawData(tg) || '–' : '–'),
        h('dt', 'Raw TPDU'), h('dd.mono', tg.tpdu)),
      tg.group ? h('div', { style: 'margin-top:.6rem;display:flex;gap:.5rem;flex-wrap:wrap;align-items:center' },
        h('button.btn.sm', { onclick: () => { selectGa(tg.dst); setFilter({ ga: tg.dst }); } }, 'Filter on ' + tg.dst),
        h('span.hint', members.length ? `Configured on: ${[...new Set(members.map(m => m.dev))].join(', ')}. A shared address does not prove that a device received this telegram.` : 'No device has this group address configured.')) : null);
  }

  function update() {
    // static controls
    const devs = S.state?.devices.map(d => d.name) || [];
    if (selDev.dataset.sig !== devs.join('|')) {
      selDev.dataset.sig = devs.join('|');
      selDev.replaceChildren(h('option', { value: '' }, 'All devices'), ...devs.map(n => h('option', { value: n }, n)));
    }
    if (document.activeElement !== search && search.value !== S.filter.text) search.value = S.filter.text;
    selDev.value = S.filter.device; selSvc.value = S.filter.service; selOrg.value = S.filter.origin;
    bTg.setAttribute('aria-pressed', String(st.tab === 'telegrams')); bLog.setAttribute('aria-pressed', String(st.tab === 'devlog'));
    const isTg = st.tab === 'telegrams';
    table.hidden = !isTg; logTable.hidden = isTg;
    for (const el of [bPause, bFollow, selSvc, selOrg]) el.hidden = !isTg;
    const af = activeFilters();
    bReset.disabled = !af.length;
    chips.hidden = !af.length && !st.paused;
    chips.replaceChildren(...af.map(([k, v]) => h('span.chip.info', `${{ text: 'Text', device: 'Device', service: 'Type', origin: 'Origin', ga: 'Group address' }[k]}: ${v}`,
      h('button.btn.quiet.sm', { style: 'height:20px;padding:0 4px', 'aria-label': 'Remove this filter', onclick: () => { setFilter({ [k]: '' }); if (k === 'ga') selectGa(null); } }, icon('close')))),
      st.paused ? h('span.chip.warn', icon('pause'), 'Paused – the simulation keeps running; new telegrams are collected') : null);

    if (isTg) {
      const all = filtered();
      const vis = st.paused ? all.filter(e => e.seq <= st.pausedAt) : all;
      const rows = vis.slice(-MAX_ROWS);
      const sig = rows.length + ':' + (rows.at(-1)?.seq || 0) + ':' + rows[0]?.seq;
      if (sig !== st.sig) {
        st.sig = sig;
        scroll.dataset.auto = '1';
        syncKeyed(tbody, rows, e => e.seq, rowFor, () => {});
        if (st.follow && !st.paused) scrollEnd();
        requestAnimationFrame(() => { scroll.dataset.auto = '0'; });
        syncSel();
      }
      const fresh = st.paused ? all.length - vis.length : 0;
      newBadge.hidden = !st.paused; setText(newBadge, fresh ? `${fresh} new` : 'no new telegrams');
      emptyMsg.hidden = !!rows.length;
      emptyMsg.replaceChildren(icon('devices'), af.length ? 'No telegram matches the filters.' : 'No telegrams yet. They appear when a device or ETS sends something.');
    } else {
      const f = S.filter, q = f.text.trim().toLowerCase();
      const rows = S.devlog.filter(e => e.seq > (S.monCutoff || 0) && (!f.device || e.dev === f.device) && (!q || `${e.dev} ${e.cat} ${e.text}`.toLowerCase().includes(q))).slice(-MAX_ROWS);
      const sig = 'L' + rows.length + ':' + (rows.at(-1)?.seq || 0) + f.device + q;
      if (sig !== st.sig) {
        st.sig = sig;
        syncKeyed(logBody, rows, e => e.seq, e => h('tr.dev-log', h('td', e.t), h('td', e.dev), h('td', h('span.tag', e.cat)), h('td', { style: 'white-space:normal' }, e.text)), () => {});
        scrollEnd();
      }
      detail.hidden = true;
      emptyMsg.hidden = !!rows.length;
      emptyMsg.replaceChildren(icon('info'), 'No device explanations yet.');
    }
    refreshTools();
  }
  on('telegrams', update); on('filter', update); on('state', () => { if (!root.isConnected || root.offsetParent) update(); });
  return { el: root, update };
}

/** The Monitor workspace: group address overview next to the full monitor. */
export function createMonitorView() {
  const root = h('div.mon-layout');
  const gaSearch = h('input.input', { type: 'search', placeholder: 'Search group addresses', 'aria-label': 'Search group addresses' });
  gaSearch.addEventListener('input', renderList);
  const gaList = h('div.list');
  const gaInfo = h('div.ga-info', { hidden: true });
  const gaPanel = h('section.panel.ga-list-panel', h('header', h('h2', 'Group addresses'), h('span.grow'), h('span.hint.cnt')),
    h('div.list-tools', h('label.search', { style: 'flex:1' }, icon('search'), gaSearch)), gaList, gaInfo);
  const mon = createMonitor({ compact: false });
  root.append(gaPanel, h('section.panel', { style: 'display:flex;flex-direction:column;min-height:0;overflow:hidden' }, mon.el));
  let counts = new Map(), countSig = '';

  function observed() {
    const sig = (S.telegrams.at(-1)?.seq || 0) + ':' + (S.monCutoff || 0) + ':' + S.telegrams.length;
    if (sig === countSig) return counts;
    countSig = sig; counts = new Map();
    for (const e of S.telegrams) {
      if (!e.tg.group) continue;
      const c = counts.get(e.tg.dst) || { n: 0, last: null, srcs: new Set() };
      c.n++; c.last = e; c.srcs.add(e.tg.src); counts.set(e.tg.dst, c);
    }
    return counts;
  }
  function pickGa(ga) {
    const next = S.gaSel === ga ? null : ga;
    selectGa(next); setFilter({ ga: next || '' });
  }
  function renderList() {
    const idx = gaIndex(), obs = observed();
    const all = new Set([...idx.keys(), ...obs.keys()]);
    const q = gaSearch.value.trim().toLowerCase();
    const items = [...all].filter(g => !q || g.includes(q) || (idx.get(g) || []).some(m => m.dev.toLowerCase().includes(q))).sort(naturalCmp);
    gaPanel.querySelector('.cnt').textContent = `${items.length}`;
    syncKeyed(gaList, items, g => g, g => h('button.ga-row', { type: 'button', onclick: () => pickGa(g) }, h('span.addr'), h('span.grow.sub.mute'), h('span.chip')), (el, g) => {
      const m = idx.get(g) || [], o = obs.get(g);
      setText(el.querySelector('.addr'), g);
      setText(el.querySelector('.sub'), m.length ? [...new Set(m.map(x => x.dev))].join(', ') : 'not configured on any device');
      const chip = el.querySelector('.chip'); setText(chip, o ? `${o.n} seen` : 'not seen'); chip.className = 'chip ' + (o ? 'info' : '');
      el.setAttribute('aria-current', String(S.gaSel === g));
    });
    if (!items.length) { if (!gaList.querySelector('.empty')) gaList.append(h('div.empty', icon('devices'), h('div', 'No group addresses yet.'), h('div.hint', 'They appear after a device is downloaded from ETS, or when telegrams are seen.'))); }
    else gaList.querySelector('.empty')?.remove();
    renderInfo(idx, obs);
  }
  function renderInfo(idx, obs) {
    const g = S.gaSel;
    gaInfo.hidden = !g;
    if (!g) return;
    const m = idx.get(g) || [], o = obs.get(g);
    const dpts = [...new Set(m.map(x => x.dpt).filter(Boolean))];
    const sig = JSON.stringify([g, m, o && [o.n, o.last.seq]]);
    if (gaInfo.dataset.sig === sig) return;
    gaInfo.dataset.sig = sig;
    gaInfo.replaceChildren(
      h('h3', { style: 'margin-bottom:.4rem' }, 'Group address ' + g),
      h('div.hint', { style: 'margin-bottom:.3rem' }, 'Configured (downloaded by ETS)'),
      m.length ? h('ul', { style: 'margin:0 0 .6rem;padding-left:1.1rem' }, m.map(x => h('li', h('b', x.dev), ' – ', x.text, ' ', h('span.flags', [...flagLetters(x.flags)].map(f => h('span', f))), x.dpt ? h('span.mute', ' · DPT ' + x.dpt) : null)))
        : h('p', { style: 'margin:0 0 .6rem' }, 'No device has this address configured.'),
      h('div.hint', 'Datapoint type (from product data): ' + (dpts.length === 1 ? dpts[0] : dpts.length > 1 ? 'Unknown DPT – devices disagree' : 'Unknown DPT')),
      h('div.hint', { style: 'margin:.5rem 0 .2rem' }, 'Observed (telegrams seen on the line)'),
      o ? h('p', { style: 'margin:0' }, `${o.n} telegram${o.n === 1 ? '' : 's'}, last at ${o.last.t}. Sources: ${[...o.srcs].map(a => a + (nameOfAddr(a) ? ' (' + nameOfAddr(a) + ')' : '')).join(', ')}.`) : h('p', { style: 'margin:0' }, 'No telegram seen for this address.'),
      h('p.hint', { style: 'margin:.6rem 0 0' }, 'A shared address does not prove that a device sent or received a telegram. Only the observed list shows what happened. The matching devices are outlined in Installation and Simulation.'),
      h('button.btn.sm', { style: 'margin-top:.5rem', onclick: () => pickGa(g) }, 'Clear selection'));
  }
  on('state', () => { if (S.view === 'monitor') renderList(); });
  on('telegrams', () => { if (S.view === 'monitor') renderList(); });
  on('ga', renderList); on('filter', () => { if (S.view === 'monitor') renderList(); });
  return { el: root, update: () => { renderList(); mon.update(); } };
}
