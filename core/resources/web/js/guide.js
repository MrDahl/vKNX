import { h, icon, setText } from './util.js';
import { S, on, selectDevice } from './bus.js';
import { productOf } from './devinfo.js';
import { modal } from './dialogs.js';
import { problems, hints, tunnels, interfaceLine, reachable, reachableFrom, lineOfAddress, ETS_MESSAGES } from './diag.js';

// ---------------------------------------------------------------- start guide (checklist)

/** The steps from "nothing" to a working device, each ticked from facts the simulator reports. */
export function guideSteps(d) {
  const tun = tunnels();
  const ok = reachable(d);
  const steps = [
    { key: 'ets', label: 'ETS is connected', done: tun.length > 0,
      todo: 'In ETS choose the connection "vKNX" (Project, Connections). It lists the interface when vKNX runs on the same network.',
      did: tun.length ? `${tun.length} connection${tun.length === 1 ? '' : 's'} open (${tun.map(t => t.address).join(', ')})` : '' },
    { key: 'iface', label: 'Interface can reach the device', done: ok, problem: !ok,
      todo: `The interface sits on line ${interfaceLine()}; this device is on line ${d.line}. Set the interface to ${d.line} or give the device an address on line ${interfaceLine()}.`,
      did: d.unassigned ? 'A device without an address counts as being on the interface line' : `Interface line ${interfaceLine()} reaches line ${d.line}` },
    { key: 'prog', label: 'Programming button pressed', done: d.programming || !d.unassigned,
      todo: 'Press the programming button below (the red indicator lights).', did: d.programming ? 'Programming mode is on' : 'Done (the address has been assigned)' },
    { key: 'addr', label: 'Physical address assigned', done: !d.unassigned,
      todo: 'In ETS: right-click the device, Program, Physical address.', did: d.address },
    { key: 'dl', label: 'Application downloaded', done: d.loaded && d.downloadState === 'complete',
      todo: d.downloadState === 'loading' ? 'A download is in progress…' : d.downloadState === 'partial' ? 'Some tables are missing: download the application again.' : 'In ETS: right-click the device, Program, Application program.',
      did: d.downloads ? `Download ${d.downloads} complete` : 'Complete' },
  ];
  if (d.secure) steps.push({ key: 'sec', label: 'Secure commissioning done', done: d.secMode && d.toolKey,
    todo: d.secMode ? 'The device is in secure mode, but ETS has not replaced the factory key yet: download again.' : 'Optional. In ETS: Add Device Certificate for the device, enable Secure Commissioning, then download.',
    did: 'Security mode on, project tool key in use' });
  let now = -1;
  steps.forEach((s, i) => { if (now < 0 && !s.done) now = i; });
  steps.forEach((s, i) => { s.state = s.problem ? 'warn' : s.done ? 'done' : i === now ? 'now' : 'todo'; });
  return steps;
}

export function renderGuide(d) {
  const steps = guideSteps(d);
  const sig = JSON.stringify(steps.map(s => [s.key, s.state, s.did, s.todo]));
  const el = h('ol.guide');
  el.dataset.sig = sig;
  for (const s of steps) {
    const mark = s.state === 'done' ? icon('check') : s.state === 'warn' ? icon('alert') : h('span.dot');
    el.append(h('li.step.' + s.state, h('span.mark', mark),
      h('div.grow', h('div.label', s.label), s.state === 'done' ? (s.did ? h('div.hint', s.did) : null) : h('div.hint', s.todo))));
  }
  return { el, sig, ready: steps.every(s => s.state === 'done' || s.key === 'sec') };
}

// ---------------------------------------------------------------- status strip (ETS connection, warnings)

export function openEtsMessages() {
  modal({ title: 'ETS messages explained', wide: true, buttons: close => [h('button.btn.primary', { onclick: close }, 'Close')],
    body: [h('p.hint', { style: 'margin-top:0' }, 'What ETS says, and what it usually means with vKNX. vKNX can only tell what it sees on its side; the lines marked "Right now" come from the last three minutes of traffic.'),
      ...(hints().length ? [h('h3', { style: 'margin:.2rem 0 .4rem' }, 'Right now'), ...hints().map(rowFor)] : []),
      h('h3', { style: 'margin:.9rem 0 .4rem' }, 'Common ETS messages'),
      h('dl.kv', ETS_MESSAGES.flatMap(([a, b]) => [h('dt', { style: 'max-width:16rem' }, a), h('dd', { style: 'font-weight:400' }, b)]))] });
}

function rowFor(p) {
  return h('div.diag.' + p.level, icon(p.level === 'info' ? 'info' : 'alert'),
    h('div.grow', h('b', p.title), h('div.hint', p.text)),
    p.dev ? h('button.btn.sm', { onclick: () => selectDevice(p.dev) }, 'Show device') : null);
}

export function createStatusStrip() {
  const root = h('section.status-strip', { 'aria-label': 'Status and warnings' });
  const head = h('div.strip-head'); const list = h('div.strip-list');
  let all = false, sig = '';
  root.append(head, list);
  function update() {
    const tun = tunnels(), iface = interfaceLine();
    const items = [...problems(), ...hints()];
    const worst = items.some(i => i.level === 'danger') ? 'danger' : items.some(i => i.level === 'warn') ? 'warn' : 'ok';
    const s = JSON.stringify([tun.map(t => t.address), iface, items.map(i => i.key + i.level), all]);
    if (s === sig) return; sig = s;
    const etsChip = h('span.chip' + (tun.length ? '.ok' : ''), icon(tun.length ? 'check' : 'circle'), tun.length ? `ETS connected · ${tun.length} connection${tun.length === 1 ? '' : 's'}` : 'No ETS connected');
    head.replaceChildren(etsChip,
      h('span.chip', 'Interface line ' + (iface || '?')),
      worst === 'ok' ? h('span.chip.ok', icon('check'), 'No problems found') : h('span.chip.' + (worst === 'danger' ? 'danger' : 'warn'), icon('alert'), `${items.filter(i => i.level !== 'info').length || items.length} to check`),
      h('span.grow'), h('button.btn.sm', { onclick: openEtsMessages }, icon('info'), 'ETS messages explained'));
    const shown = all ? items : items.slice(0, 3);
    list.replaceChildren(...shown.map(rowFor), items.length > 3 ? h('button.btn.quiet.sm', { onclick: () => { all = !all; sig = ''; update(); } }, all ? 'Show fewer' : `Show ${items.length - 3} more`) : null);
    root.classList.toggle('calm', !items.length);
  }
  return { el: root, update };
}

// ---------------------------------------------------------------- topology diagram

const SVG = 'http://www.w3.org/2000/svg';
const sv = (name, attrs = {}, ...kids) => { const e = document.createElementNS(SVG, name); for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v); for (const c of kids.flat()) if (c != null) e.append(c.nodeType ? c : document.createTextNode(String(c))); return e; };

/** Areas, lines and couplers as a small diagram; the interface line is marked; lines that carried a telegram lately glow. */
export function createTopologyDiagram() {
  const root = h('div.topo-diagram', { role: 'img', 'aria-label': 'Topology diagram of the lines, couplers and the ETS interface' });
  let sig = '';
  function activeLines() {
    const now = Date.now(), set = new Map(), iface = interfaceLine();
    for (const e of S.telegrams.slice(-60)) {
      if (now - e.ts > 2500) continue;
      const tg = e.tg;
      const l = tg.origin === 'ip' ? iface : lineOfAddress(tg.src);
      if (l) set.set(l, Math.max(set.get(l) || 0, e.ts));
    }
    return set;
  }
  function draw() {
    const devs = S.state?.devices || [], iface = interfaceLine();
    const lines = new Set(iface ? [iface] : []);
    const couplers = [];
    for (const d of devs) {
      if (d.product === 'coupler') { const v = d.view || {}; if (v.placement) { lines.add(v.placement); if (v.mainLine) lines.add(v.mainLine); couplers.push({ a: v.placement, b: v.mainLine, d }); } }
      else if (!d.unassigned) lines.add(d.line);
    }
    const areas = new Map();
    for (const l of lines) { const a = l.split('.')[0]; if (!areas.has(a)) areas.set(a, []); areas.get(a).push(l); }
    for (const ls of areas.values()) ls.sort((x, y) => +x.split('.')[1] - +y.split('.')[1]);
    const order = [...areas.keys()].sort((a, b) => a - b);
    const hot = activeLines();
    const reach = iface ? reachableFrom(iface) : new Set();
    const cnt = l => devs.filter(d => d.product !== 'coupler' && !d.unassigned && d.line === l).length;
    const sig2 = JSON.stringify([order.map(a => areas.get(a)), iface, couplers.map(c => [c.a, c.b, c.d.loaded]), [...hot.keys()], [...lines].map(cnt), S.state?.routing]);
    if (sig2 === sig) return; sig = sig2;
    if (!order.length) { root.replaceChildren(h('div.hint', 'No lines yet. They appear as soon as there is a device or an ETS connection.')); return; }
    const colW = 190, W = Math.max(400, order.length * colW);
    const needBack = order.length > 1 || lines.has('0.0') || couplers.some(c => c.b === '0.0');
    const mainOf = a => a + '.0';
    const subsOf = a => areas.get(a).filter(l => l.split('.')[1] !== '0');
    const startY = a => (needBack ? 82 : 24) + (lines.has(mainOf(a)) && a !== '0' ? 56 : 0) + 8;
    const maxSubs = Math.max(1, ...order.map(a => subsOf(a).length));
    const H = Math.max(...order.map(a => startY(a))) + maxSubs * 56 + 8;
    const svg = sv('svg', { viewBox: `0 0 ${W} ${H}`, width: '100%', class: 'topo-svg', preserveAspectRatio: 'xMidYMin meet', style: `max-width:${Math.round(W * 1.25)}px` });
    const x0 = a => order.indexOf(a) * colW + colW / 2;
    const bar = (l, x, y, w, label, extra = {}) => {
      const g = sv('g', { class: 'tbar' + (hot.has(l) ? ' hot' : '') + (l === iface ? ' iface' : '') + (iface && !reach.has(l) && S.state?.routing !== false ? ' cut' : '') });
      g.append(sv('rect', { x: x - w / 2, y: y - 16, width: w, height: 32, rx: 6 }),
        sv('text', { x, y: y - 2, 'text-anchor': 'middle', class: 't1' }, label),
        sv('text', { x, y: y + 11, 'text-anchor': 'middle', class: 't2' }, extra.sub || ''));
      if (l === iface) g.append(sv('text', { x: x + w / 2 - 4, y: y - 21, 'text-anchor': 'end', class: 'tplug' }, 'ETS interface'));
      return g;
    };
    const link = (x1, y1, x2, y2, ok) => sv('line', { x1, y1, x2, y2, class: 'tlink' + (ok ? '' : ' none') });
    const hasC = (a, b) => couplers.some(c => (c.a === a && c.b === b) || (c.a === b && c.b === a));
    if (needBack) svg.append(bar('0.0', W / 2, 24, W - 24, 'Backbone 0.0', { sub: '' }));
    for (const a of order) {
      const x = x0(a), main = mainOf(a), subs = subsOf(a), showMain = lines.has(main) && a !== '0';
      let parentY = needBack ? 24 : null;
      if (showMain) {
        if (needBack) svg.append(link(x, 40, x, 66, hasC(main, '0.0')));
        svg.append(bar(main, x, 82, colW - 30, 'Main line ' + main, { sub: cnt(main) ? cnt(main) + ' device(s)' : '' }));
        parentY = 82;
      }
      subs.forEach((l, i) => {
        const y = startY(a) + 16 + i * 56 - 8 + (i === 0 ? 0 : 0);
        if (parentY !== null || i > 0) svg.append(link(x, i === 0 ? parentY + 16 : y - 40, x, y - 16, hasC(l, showMain ? main : '0.0')));
        svg.append(bar(l, x, y, colW - 44, 'Line ' + l, { sub: cnt(l) ? cnt(l) + ' device(s)' : 'no devices' }));
        if (hasC(l, main)) svg.append(sv('text', { x: x + 6, y: y - 22, class: 'tc' }, 'coupler'));
      });
    }
    root.replaceChildren(svg, h('div.hint', { style: 'margin-top:.3rem' }, S.state?.routing === false ? 'Line routing is switched off: every device hears everything.' : 'Solid link = a coupler exists. Dashed = none, so telegrams stop there. A red-edged line cannot be reached from the ETS interface. A glowing line carried a telegram a moment ago.'));
  }
  return { el: root, update: draw };
}
