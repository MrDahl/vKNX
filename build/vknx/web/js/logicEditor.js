import { h, icon } from './util.js';
import { api } from './api.js';
import { S, device } from './bus.js';
import { toast, confirmDialog } from './dialogs.js';

/**
 * The logic editor of the logic module: a simplified GateCraft (gates, delay, stairway timer, impulse relay, clock) whose
 * inputs and outputs are the ones enabled in the ETS parameters. It opens as a full-screen window from the card of a logic
 * module and nowhere else. No file export or import, no sharing: the circuit lives in the device.
 *
 * Circuit text (the same format the backend stores): "n id KIND x y param" per element, "w src dst port" per wire.
 */
const NS = 'http://www.w3.org/2000/svg';
const GRID = 20, W = 108;
const GATES = ['BUFFER', 'NOT', 'AND', 'NAND', 'OR', 'NOR', 'XOR', 'XNOR'];
const SINGLE = new Set(['BUFFER', 'NOT']);
const SYMBOL = { BUFFER: '1', NOT: '¬', AND: '&', NAND: '¬&', OR: '≥1', NOR: '¬≥1', XOR: '=1', XNOR: '¬=1' };

const svg = (tag, attrs = {}, ...kids) => {
  const el = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) if (v !== undefined && v !== null && v !== false) el.setAttribute(k, v === true ? '' : v);
  for (const k of kids.flat()) if (k != null && k !== false) el.append(k.nodeType ? k : document.createTextNode(String(k)));
  return el;
};

const inputCount = n => n.kind === 'IN' || n.kind === 'CLOCK' ? 0 : GATES.includes(n.kind) ? n.param : 1;
const nodeW = n => (n.kind === 'IN' || n.kind === 'OUT') ? 150 : W;
const nodeH = n => Math.max(46, 26 + Math.max(inputCount(n), 1) * 22);
const portIn = (n, i) => ({ x: n.x, y: n.y + (nodeH(n) - inputCount(n) * 22) / 2 + i * 22 + 11 });
const portOut = n => ({ x: n.x + nodeW(n), y: n.y + nodeH(n) / 2 });

function parse(text) {
  const nodes = new Map(), wires = [];
  for (const line of (text || '').split(/\r?\n/)) {
    const f = line.trim().split(/\s+/);
    if (f[0] === 'n' && f.length === 6) nodes.set(f[1], { id: f[1], kind: f[2], x: +f[3], y: +f[4], param: +f[5] });
    else if (f[0] === 'w' && f.length === 4) wires.push({ src: f[1], dst: f[2], port: +f[3] });
  }
  return { nodes, wires };
}
const serialize = m => 'v1\n' + [...m.nodes.values()].map(n => `n ${n.id} ${n.kind} ${Math.round(n.x)} ${Math.round(n.y)} ${n.param}`).join('\n') + '\n'
  + m.wires.map(w => `w ${w.src} ${w.dst} ${w.port}`).join('\n') + '\n';

export function openLogicEditor(devName) {
  let model = parse(''), saved = '', sel = null, selWire = null, armed = null, live = {}, view = { tx: 40, ty: 40, k: 1 }, dead = false;
  const root = h('div.lgx', { role: 'dialog', 'aria-modal': 'true', 'aria-label': 'Logic editor' });
  const status = h('span.lgx-status');
  const saveBtn = h('button.btn.primary', { type: 'button', onclick: () => save() }, icon('check'), 'Save');
  const closeBtn = h('button.btn', { type: 'button', onclick: () => close() }, icon('close'), 'Close');
  const toolbox = h('div.lgx-tools');
  const inspector = h('div.lgx-insp');
  const world = svg('g'), wireLayer = svg('g'), nodeLayer = svg('g');
  world.append(wireLayer, nodeLayer);
  const canvas = svg('svg', { class: 'lgx-canvas', tabindex: 0 }, world);
  const hint = h('div.lgx-hint');
  root.append(
    h('header.lgx-head', h('h2', 'Logic editor – ', devName), status, h('span.grow'), saveBtn, closeBtn),
    h('div.lgx-body', h('aside.lgx-side', toolbox, inspector), h('div.lgx-stage', canvas, hint)));

  const dev = () => device(devName);
  const dirty = () => serialize(model) !== saved;
  const refreshStatus = () => { const d = dirty(); status.textContent = d ? 'Unsaved changes' : 'Saved'; status.className = 'lgx-status ' + (d ? 'warn' : 'ok'); };
  const nextId = () => { let i = 1; while (model.nodes.has('n' + i)) i++; return 'n' + i; };
  const snap = v => Math.round(v / GRID) * GRID;

  function ioName(kind, i) {
    const v = dev()?.view || {};
    const on = kind === 'IN' ? v['ie' + i] : v['oe' + i];
    const nm = kind === 'IN' ? v['in' + i] : v['on' + i];
    return { enabled: on === true, name: on ? nm : '' };
  }
  function labelOf(n) {
    switch (n.kind) {
      case 'IN': { const o = ioName('IN', n.param); return `I${n.param} ${o.name || (o.enabled ? '' : '(not enabled in ETS)')}`.trim(); }
      case 'OUT': { const o = ioName('OUT', n.param); return `O${n.param} ${o.name || (o.enabled ? '' : '(not enabled in ETS)')}`.trim(); }
      case 'DELAY': return 'DELAY ' + (n.param / 1000) + ' s';
      case 'STAIRWAY': return 'STAIRWAY ' + (n.param / 1000) + ' s';
      case 'TOGGLE': return 'IMPULSE';
      case 'CLOCK': return 'CLOCK ' + (n.param / 10) + ' Hz';
      default: return n.kind;
    }
  }

  // ------------------------------------------------------------------ drawing
  function render() {
    world.setAttribute('transform', `translate(${view.tx} ${view.ty}) scale(${view.k})`);
    wireLayer.replaceChildren(...model.wires.map((w, i) => {
      const s = model.nodes.get(w.src), d = model.nodes.get(w.dst);
      if (!s || !d) return null;
      const a = portOut(s), b = portIn(d, w.port), dx = Math.max(40, Math.abs(b.x - a.x) / 2);
      const path = `M${a.x} ${a.y} C${a.x + dx} ${a.y} ${b.x - dx} ${b.y} ${b.x} ${b.y}`;
      return svg('g', { 'data-wire': i },
        svg('path', { d: path, class: 'lgx-wire' + (live[w.src] ? ' on' : '') + (selWire === i ? ' sel' : '') }),
        svg('path', { d: path, class: 'lgx-wire-hit' }));
    }));
    nodeLayer.replaceChildren(...[...model.nodes.values()].map(n => {
      const nw = nodeW(n), nh = nodeH(n), on = !!live[n.id];
      const ioBad = (n.kind === 'IN' || n.kind === 'OUT') && !ioName(n.kind, n.param).enabled;
      const g = svg('g', { 'data-node': n.id, class: 'lgx-node ' + (n.kind === 'IN' ? 'in ' : n.kind === 'OUT' ? 'out ' : '') + (on ? 'on ' : '') + (sel === n.id ? 'sel ' : '') + (ioBad ? 'bad' : ''), transform: `translate(${n.x} ${n.y})` },
        svg('rect', { width: nw, height: nh, rx: 8 }),
        GATES.includes(n.kind) ? svg('text', { x: nw / 2, y: nh / 2 - 2, class: 'sym', 'text-anchor': 'middle' }, SYMBOL[n.kind]) : null,
        svg('text', { x: nw / 2, y: GATES.includes(n.kind) ? nh / 2 + 16 : nh / 2 + 5, class: 'lbl', 'text-anchor': 'middle' }, labelOf(n)));
      for (let i = 0; i < inputCount(n); i++) {
        const p = portIn({ ...n, x: 0, y: 0 }, i);
        g.append(svg('circle', { cx: p.x, cy: p.y, r: 7, class: 'lgx-port in' + (armed && armed !== n.id ? ' ready' : ''), 'data-in': i }));
      }
      if (n.kind !== 'OUT') { const p = portOut({ ...n, x: 0, y: 0 }); g.append(svg('circle', { cx: p.x, cy: p.y, r: 7, class: 'lgx-port out' + (armed === n.id ? ' armed' : ''), 'data-out': 1 })); }
      return g;
    }));
    hint.hidden = model.nodes.size > 0;
    refreshStatus();
  }

  function toolButton(label, kind, param, extra) {
    return h('button.btn.lgx-tool', { type: 'button', title: extra?.title || label, disabled: extra?.disabled, onclick: () => add(kind, param) }, label);
  }
  function buildToolbox() {
    const d = dev(), v = d?.view || {};
    const used = (k, i) => [...model.nodes.values()].some(n => n.kind === k && n.param === i);
    const ins = [], outs = [];
    for (let i = 1; i <= 16; i++) {
      if (v['ie' + i]) ins.push(toolButton(`I${i} ${v['in' + i] || ''}`.trim(), 'IN', i, { disabled: used('IN', i), title: 'Place input ' + i }));
      if (v['oe' + i]) outs.push(toolButton(`O${i} ${v['on' + i] || ''}`.trim(), 'OUT', i, { disabled: used('OUT', i), title: 'Place output ' + i }));
    }
    toolbox.replaceChildren(
      h('h3', 'Inputs'), ins.length ? h('div.lgx-grid', ins) : h('div.hint', 'No input is enabled. Enable inputs in the ETS parameters (page "Inputs 1-8") and download.'),
      h('h3', 'Outputs'), outs.length ? h('div.lgx-grid', outs) : h('div.hint', 'No output is enabled. Enable outputs in the ETS parameters (page "Outputs 1-8") and download.'),
      h('h3', 'Gates'), h('div.lgx-grid', GATES.map(k => toolButton(k, k, SINGLE.has(k) ? 1 : 2))),
      h('h3', 'Timing'), h('div.lgx-grid',
        toolButton('DELAY', 'DELAY', 1000, { title: 'Follows its input after a set time' }),
        toolButton('STAIRWAY', 'STAIRWAY', 30000, { title: 'On for a set time after each rising edge' }),
        toolButton('IMPULSE', 'TOGGLE', 0, { title: 'Every rising edge flips the output' }),
        toolButton('CLOCK', 'CLOCK', 10, { title: 'Square wave' })));
  }

  function buildInspector() {
    const n = sel && model.nodes.get(sel);
    if (selWire !== null && model.wires[selWire]) {
      inspector.replaceChildren(h('h3', 'Wire'), h('button.btn.danger', { type: 'button', onclick: () => deleteSelection() }, icon('trash'), 'Delete wire'));
      return;
    }
    if (!n) { inspector.replaceChildren(h('h3', 'How it works'), h('div.hint', 'Click a tool to place it. Drag from the round dot on the right side of an element to a dot on the left side of another to wire them. Click an element or a wire to change or delete it. Drag the background to move around, scroll to zoom.')); return; }
    const rows = [h('h3', labelOf(n))];
    if (GATES.includes(n.kind)) {
      rows.push(h('label', 'Type', h('select', { onchange: e => { n.kind = e.target.value; n.param = SINGLE.has(n.kind) ? 1 : Math.max(2, n.param); prune(n); change(); } },
        GATES.map(k => h('option', { value: k, selected: k === n.kind }, k)))));
      if (!SINGLE.has(n.kind)) rows.push(h('label', 'Inputs', h('input', { type: 'number', min: 2, max: 16, value: n.param, onchange: e => { n.param = Math.min(16, Math.max(2, +e.target.value || 2)); prune(n); change(); } })));
    }
    if (n.kind === 'DELAY' || n.kind === 'STAIRWAY')
      rows.push(h('label', 'Time (seconds)', h('input', { type: 'number', min: 0, max: 3599, step: 0.1, value: n.param / 1000, onchange: e => { n.param = Math.round(Math.min(3599, Math.max(0, +e.target.value || 0)) * 1000); change(); } })));
    if (n.kind === 'CLOCK')
      rows.push(h('label', 'Frequency (Hz)', h('input', { type: 'number', min: 0.1, max: 20, step: 0.1, value: n.param / 10, onchange: e => { n.param = Math.round(Math.min(20, Math.max(0.1, +e.target.value || 1)) * 10); change(); } })));
    if (n.kind === 'DELAY') rows.push(h('div.hint', 'The output follows the input after this time, when it goes on and when it goes off. A shorter pulse is swallowed.'));
    if (n.kind === 'STAIRWAY') rows.push(h('div.hint', 'A rising edge switches the output on for this time. A new rising edge starts the time again.'));
    if (n.kind === 'TOGGLE') rows.push(h('div.hint', 'Every rising edge on the input flips the output (impulse relay).'));
    rows.push(h('button.btn.danger', { type: 'button', onclick: () => deleteSelection() }, icon('trash'), 'Delete'));
    inspector.replaceChildren(...rows);
  }

  // ------------------------------------------------------------------ editing
  const change = () => { render(); buildToolbox(); buildInspector(); };
  /** Removes wires that end at an input the element no longer has. */
  function prune(n) { model.wires = model.wires.filter(w => w.dst !== n.id || w.port < inputCount(n)); }

  function add(kind, param) {
    if (model.nodes.size >= 200) { toast('The circuit is full (200 elements).', 'error'); return; }
    const r = canvas.getBoundingClientRect();
    let x = snap((r.width / 2 - view.tx) / view.k - W / 2), y = snap((r.height / 2 - view.ty) / view.k - 20);
    while ([...model.nodes.values()].some(o => o.x === x && o.y === y)) { x += GRID; y += GRID; }
    const n = { id: nextId(), kind, x, y, param };
    model.nodes.set(n.id, n);
    sel = n.id; selWire = null;
    change();
  }

  function deleteSelection() {
    if (selWire !== null) model.wires.splice(selWire, 1);
    else if (sel) { model.nodes.delete(sel); model.wires = model.wires.filter(w => w.src !== sel && w.dst !== sel); }
    sel = null; selWire = null; armed = null;
    change();
  }

  function connect(srcId, dstId, port) {
    const s = model.nodes.get(srcId), d = model.nodes.get(dstId);
    if (!s || !d || srcId === dstId || s.kind === 'OUT' || port >= inputCount(d)) return false;
    model.wires = model.wires.filter(w => !(w.dst === dstId && w.port === port));
    model.wires.push({ src: srcId, dst: dstId, port });
    return true;
  }

  const toWorld = e => { const r = canvas.getBoundingClientRect(); return { x: (e.clientX - r.left - view.tx) / view.k, y: (e.clientY - r.top - view.ty) / view.k }; };

  canvas.addEventListener('pointerdown', e => {
    canvas.focus();
    const outP = e.target.closest('[data-out]'), inP = e.target.closest('[data-in]'), nodeEl = e.target.closest('[data-node]'), wireEl = e.target.closest('[data-wire]');
    if (outP && nodeEl) { startWire(e, nodeEl.dataset.node); return; }
    if (inP && nodeEl && armed) { if (connect(armed, nodeEl.dataset.node, +inP.dataset.in)) { armed = null; change(); } return; }
    if (nodeEl) { startDrag(e, nodeEl.dataset.node); return; }
    if (wireEl) { selWire = +wireEl.dataset.wire; sel = null; armed = null; render(); buildInspector(); return; }
    // background: pan
    sel = null; selWire = null; armed = null; render(); buildInspector();
    const x0 = e.clientX, y0 = e.clientY, tx0 = view.tx, ty0 = view.ty;
    const move = ev => { view.tx = tx0 + ev.clientX - x0; view.ty = ty0 + ev.clientY - y0; render(); };
    const up = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); };
    window.addEventListener('pointermove', move); window.addEventListener('pointerup', up);
  });

  function startDrag(e, id) {
    const n = model.nodes.get(id); sel = id; selWire = null; armed = null;
    const p0 = toWorld(e), x0 = n.x, y0 = n.y; let moved = false;
    const move = ev => { const p = toWorld(ev); const nx = snap(x0 + p.x - p0.x), ny = snap(y0 + p.y - p0.y); if (nx !== n.x || ny !== n.y) { n.x = nx; n.y = ny; moved = true; render(); } };
    const up = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); buildInspector(); buildToolbox(); if (!moved) render(); };
    window.addEventListener('pointermove', move); window.addEventListener('pointerup', up);
    render(); buildInspector();
  }

  /** Drag from an output dot to an input dot; a plain click arms the dot so a second click on an input dot connects (touch). */
  function startWire(e, srcId) {
    const x0 = e.clientX, y0 = e.clientY; let dragged = false;
    const temp = svg('path', { class: 'lgx-wire temp' });
    wireLayer.append(temp);
    const s = model.nodes.get(srcId), a = portOut(s);
    const move = ev => {
      if (Math.abs(ev.clientX - x0) + Math.abs(ev.clientY - y0) > 6) dragged = true;
      const p = toWorld(ev); temp.setAttribute('d', `M${a.x} ${a.y} L${p.x} ${p.y}`);
    };
    const up = ev => {
      window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); temp.remove();
      if (!dragged) { armed = armed === srcId ? null : srcId; render(); return; }
      const el = document.elementFromPoint(ev.clientX, ev.clientY), inP = el?.closest?.('[data-in]'), nodeEl = el?.closest?.('[data-node]');
      if (inP && nodeEl && connect(srcId, nodeEl.dataset.node, +inP.dataset.in)) change(); else render();
    };
    window.addEventListener('pointermove', move); window.addEventListener('pointerup', up);
  }

  canvas.addEventListener('wheel', e => {
    e.preventDefault();
    const r = canvas.getBoundingClientRect(), k = Math.min(2.5, Math.max(0.3, view.k * (e.deltaY < 0 ? 1.1 : 1 / 1.1)));
    const wx = (e.clientX - r.left - view.tx) / view.k, wy = (e.clientY - r.top - view.ty) / view.k;
    view.k = k; view.tx = e.clientX - r.left - wx * k; view.ty = e.clientY - r.top - wy * k; render();
  }, { passive: false });

  function fit() {
    if (!model.nodes.size) { view = { tx: 40, ty: 40, k: 1 }; render(); return; }
    const r = canvas.getBoundingClientRect(); let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
    for (const n of model.nodes.values()) { x0 = Math.min(x0, n.x); y0 = Math.min(y0, n.y); x1 = Math.max(x1, n.x + nodeW(n)); y1 = Math.max(y1, n.y + nodeH(n)); }
    const k = Math.min(1.2, Math.max(0.3, Math.min((r.width - 80) / (x1 - x0), (r.height - 80) / (y1 - y0))));
    view = { k, tx: (r.width - (x1 - x0) * k) / 2 - x0 * k, ty: (r.height - (y1 - y0) * k) / 2 - y0 * k }; render();
  }

  // ------------------------------------------------------------------ load, save, live values, close
  async function load() {
    try {
      const r = await fetch('/api/logic?dev=' + encodeURIComponent(devName), { cache: 'no-store' });
      if (!r.ok) throw new Error(await r.text());
      const j = await r.json();
      model = parse(j.circuit); saved = serialize(model); live = j.live || {};
      change(); fit();
    } catch (e) { toast('Could not load the circuit: ' + e.message, 'error'); }
  }

  async function save() {
    const text = serialize(model);
    try {
      const r = await fetch('/api/logic?dev=' + encodeURIComponent(devName), { method: 'POST', body: text });
      if (!r.ok) throw new Error((await r.text()) || 'The simulator refused the circuit.');
      saved = text; refreshStatus(); toast('Circuit saved. It runs now.', 'ok', 2500);
    } catch (e) { toast('Not saved: ' + e.message, 'error'); }
  }

  async function poll() {
    if (dead) return;
    try {
      const r = await fetch('/api/logic?dev=' + encodeURIComponent(devName) + '&live=1', { cache: 'no-store' });
      if (r.ok) { live = (await r.json()).live || {}; render(); }
    } catch (e) { /* the connection banner of the app reports this */ }
    setTimeout(poll, 400);
  }

  async function close() {
    if (dirty() && !await confirmDialog({ title: 'Unsaved changes', message: 'The circuit has changes that are not saved. Close without saving?', confirmLabel: 'Close without saving', danger: true })) return;
    dead = true; document.removeEventListener('keydown', key, true); root.remove();
  }

  function key(e) {
    if (!document.body.contains(root) || document.querySelector('dialog[open]')) return;
    const typing = /^(INPUT|SELECT|TEXTAREA)$/.test(e.target.tagName);
    if (e.key === 's' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); save(); }
    else if (e.key === 'Escape') { if (armed) { armed = null; render(); } else close(); }
    else if ((e.key === 'Delete' || e.key === 'Backspace') && !typing && (sel || selWire !== null)) { e.preventDefault(); deleteSelection(); }
  }
  document.addEventListener('keydown', key, true);

  document.body.append(root);
  hint.append(h('b', 'Empty circuit'), h('div', 'Place inputs and outputs from the left, add gates between them, wire them up and press Save.'));
  buildToolbox(); buildInspector(); render();
  load();
  poll();
  closeBtn.focus();
  return root;
}
