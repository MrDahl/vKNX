import { h, icon, setText, pref, setPref } from './util.js';
import { S, on, selectDevice } from './bus.js';
import { productOf } from './devinfo.js';
import { modal } from './dialogs.js';
import { problems, hints, tunnels, interfaceLine, reachable, reachableFrom, lineOfAddress, ETS_MESSAGES } from './diag.js';

// ---------------------------------------------------------------- start guide (checklist)

/**
 * The steps from "nothing" to a working device, each ticked from facts the simulator reports. A step that is still open
 * is worded as a task ("Connect ETS"), a finished one as a fact ("ETS connected"); `short` is the one-line next action.
 */
export function guideSteps(d) {
  const tun = tunnels();
  const ok = reachable(d);
  const steps = [
    { key: 'ets', label: 'Connect ETS', doneLabel: 'ETS connected', short: 'Connect ETS to continue', done: tun.length > 0,
      todo: 'In ETS choose the connection "vKNX" (Project, Connections). It lists the interface when vKNX runs on the same network.',
      did: tun.length ? `${tun.length} connection${tun.length === 1 ? '' : 's'} open (${tun.map(t => t.address).join(', ')})` : '' },
    { key: 'iface', label: 'Check the interface line', doneLabel: 'Interface can reach the device', short: 'Fix the interface line', done: ok, problem: !ok,
      todo: `The interface sits on line ${interfaceLine()}; this device is on line ${d.line}. Set the interface to ${d.line} or give the device an address on line ${interfaceLine()}.`,
      did: d.unassigned ? 'A device without an address counts as being on the interface line' : `Interface line ${interfaceLine()} reaches line ${d.line}` },
    { key: 'prog', label: 'Press the programming button', doneLabel: 'Programming button pressed', short: 'Press the programming button (top right)', done: d.programming || !d.unassigned,
      todo: 'Press the programming button at the top of this page (the red indicator lights).', did: d.programming ? 'Programming mode is on' : 'Done (the address has been assigned)' },
    { key: 'addr', label: 'Assign the physical address', doneLabel: 'Physical address assigned', short: 'Assign the physical address in ETS', done: !d.unassigned,
      todo: 'In ETS: right-click the device, Program, Physical address.', did: d.address },
    { key: 'dl', label: 'Download the application', doneLabel: 'Application downloaded', short: 'Download the application from ETS', done: d.loaded && d.downloadState === 'complete',
      todo: d.downloadState === 'loading' ? 'A download is in progress…' : d.downloadState === 'partial' ? 'Some tables are missing: download the application again.' : 'In ETS: right-click the device, Program, Application program.',
      did: d.downloads ? `Download ${d.downloads} complete` : 'Complete' },
  ];
  if (d.secure) steps.push({ key: 'sec', label: 'Commission securely (optional)', doneLabel: 'Secure commissioning done', short: 'Optional: commission securely', done: d.secMode && d.toolKey,
    todo: d.secMode ? 'The device is in secure mode, but ETS has not replaced the factory key yet: download again.' : 'Optional. In ETS: Add Device Certificate for the device, enable Secure Commissioning, then download.',
    did: 'Security mode on, project tool key in use' });
  let now = -1;
  steps.forEach((s, i) => { if (now < 0 && !s.done) now = i; });
  steps.forEach((s, i) => { s.state = s.problem ? 'warn' : s.done ? 'done' : i === now ? 'now' : 'todo'; });
  return steps;
}

/** The compact checklist: finished steps are one line, only the current step shows its explanation. */
export function renderGuide(d) {
  const steps = guideSteps(d);
  const sig = JSON.stringify(steps.map(s => [s.key, s.state, s.did, s.todo]));
  const el = h('ol.guide');
  el.dataset.sig = sig;
  for (const s of steps) {
    const mark = s.state === 'done' ? icon('check') : s.state === 'warn' ? icon('alert') : h('span.dot');
    const open = s.state === 'now' || s.state === 'warn';
    el.append(h('li.step.' + s.state, h('span.mark', mark),
      h('div.grow', h('div.label', s.state === 'done' ? s.doneLabel : s.label),
        open ? h('div.hint', s.todo) : s.state === 'done' && s.did ? h('div.hint.faint-hint', s.did) : null)));
  }
  const cur = steps.find(s => s.state === 'warn') || steps.find(s => s.state === 'now');
  const ready = steps.every(s => s.state === 'done' || s.key === 'sec');
  return { el, sig, ready, next: ready ? null : cur };
}

/** "Next: ..." line for a device: the short text of the first open step, or a ready message. */
export function createNextBar() {
  const el = h('div.next-bar'); let sig = '';
  function update(d, g) {
    const cur = g.next, s = JSON.stringify([d.name, cur?.key, cur?.state, g.ready]);
    if (s === sig) return; sig = s;
    if (cur && !g.ready) el.className = 'next-bar ' + (cur.state === 'warn' ? 'warn' : 'now');
    else el.className = 'next-bar ok';
    el.replaceChildren(icon(cur && !g.ready ? (cur.state === 'warn' ? 'alert' : 'chevron') : 'check'),
      h('span.grow', cur && !g.ready ? [h('b', 'Next: '), cur.short] : [h('b', 'Ready. '), 'Operate it in Simulation.']));
  }
  return { el, update };
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

function compactRow(p) {
  return h('details.diag.compact.' + p.level, h('summary', icon(p.level === 'info' ? 'info' : 'alert'), h('span.grow.ellipsis', p.title),
    p.dev ? h('button.btn.sm', { onclick: ev => { ev.preventDefault(); selectDevice(p.dev); } }, 'Show device') : null, h('span.more', icon('chevron'))),
    h('div.hint', p.text));
}

export function createStatusStrip() {
  const root = h('section.status-strip', { 'aria-label': 'Status and warnings' });
  const head = h('div.strip-head'); const list = h('div.strip-list');
  let open = pref('stripOpen', true), sig = '';
  root.append(head, list);
  function update() {
    const tun = tunnels(), iface = interfaceLine();
    const items = [...problems(), ...hints()];
    const worst = items.some(i => i.level === 'danger') ? 'danger' : items.some(i => i.level === 'warn') ? 'warn' : 'ok';
    const s = JSON.stringify([tun.map(t => t.address), iface, items.map(i => i.key + i.level), open]);
    if (s === sig) return; sig = s;
    const etsChip = h('span.chip' + (tun.length ? '.ok' : ''), icon(tun.length ? 'check' : 'circle'), tun.length ? `ETS connected · ${tun.length} connection${tun.length === 1 ? '' : 's'}` : 'No ETS connected');
    const toggle = items.length ? h('button.btn.sm.' + (worst === 'danger' ? 'danger-outline' : 'quiet'), { 'aria-expanded': String(open), title: open ? 'Hide the warnings' : 'Show the warnings',
      onclick: () => { open = !open; setPref('stripOpen', open); sig = ''; update(); } }, icon(worst === 'ok' ? 'info' : 'alert'), `${items.length} ${items.length === 1 ? 'message' : 'messages'}`, h('span' + (open ? '.chev.open' : '.chev'), icon('chevron'))) : null;
    head.replaceChildren(etsChip, h('span.chip', 'Interface line ' + (iface || '?')),
      items.length ? toggle : h('span.chip.ok', icon('check'), 'No problems found'),
      h('span.grow'), h('button.btn.sm.quiet', { onclick: openEtsMessages }, icon('info'), 'ETS messages explained'));
    list.hidden = !items.length || !open;
    list.replaceChildren(...items.map(compactRow));
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
    // y of a main line: under the backbone if there is one, else at the top (with room for the "ETS interface" label)
    const mainY = needBack ? 82 : 34;
    const startY = a => mainY + (lines.has(mainOf(a)) && a !== '0' ? 56 : 0) + 8;
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
        svg.append(bar(main, x, mainY, colW - 30, 'Main line ' + main, { sub: cnt(main) ? cnt(main) + ' device(s)' : '' }));
        parentY = mainY;
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
