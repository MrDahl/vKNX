import { h, icon, setText, setCls, setAttr, setStyle, naturalCmp } from '../util.js';
import { api } from '../api.js';
import { S, on, emit, selectDevice } from '../bus.js';
import { productOf, statusOf, pointsOf } from '../devinfo.js';
import { createDetail } from '../detail.js';
import { operate, kelvinCss, holdButton } from '../cards.js';
import { interfaceLine, lineOfAddress } from '../diag.js';
import { paintIcon } from '../iconStore.js';
import { openAddDevice } from '../addDevice.js';

/**
 * Board: the devices the way they sit in a distribution board. Devices made for a DIN rail (couplers, actuators, meters)
 * are drawn as modules on one rail per line, with the bus cable of that line underneath. Devices that sit in the rooms
 * (push buttons, display, sensors) are shown beside the board. Everything here is read from the state the other views
 * use; the only things to operate are the programming buttons and the keys, as on a real device.
 */

const DIN = new Set(['coupler', 'switch-actuator', 'dimmer', 'blind-actuator', 'colour-dimmer', 'heating-actuator', 'energy-meter', 'logic']);
/** Width of a module in "TE" (one TE is 18 mm on a real rail). */
const TE = { coupler: 3, 'switch-actuator': 5, dimmer: 4, 'blind-actuator': 4, 'colour-dimmer': 4, 'heating-actuator': 5, 'energy-meter': 3, logic: 4 };
const RAIL_TE = 16;
const baseKey = d => d.product.replace(/^secure-/, '');
const isDin = d => DIN.has(baseKey(d));
const letter = i => String.fromCharCode(65 + i);
/** A coupler belongs to the line it serves (x.y.0 sits on line x.y), other devices to the line of their address. */
const lineOf = d => (baseKey(d) === 'coupler' && d.view?.placement) || d.line;
const lineColour = l => { const y = +String(l).split('.')[1]; return y === 0 ? 'var(--ln-main)' : `var(--ln${((y || 1) - 1) % 4})`; };
const addrCmp = (a, b) => naturalCmp(a.address, b.address);

// ------------------------------------------------------------------ the front of a module

function lampFace(count, coloured) {
  const cells = Array.from({ length: count }, (_, i) => {
    const fill = h('i'); const val = h('span.bm-val'); const track = h('div.bm-bar', fill);
    return { el: h('div.bm-ch', track, val, h('small', letter(i))), fill, val };
  });
  return {
    el: h('div.bm-chs', cells.map(c => c.el)),
    update(v) {
      cells.forEach((c, i) => {
        const n = i + 1, lv = v['level' + n] ?? 0;
        c.el.hidden = v['e' + n] === false;
        setStyle(c.fill, 'height', lv + '%');
        const css = coloured && lv > 0 ? (v['w' + n] ? kelvinCss(v['k' + n] ?? 3000) : (v['rgb' + n] || '#FFFFFF')) : '';
        setStyle(c.fill, 'background', css);
        setText(c.val, lv > 0 ? lv + ' %' : 'off');
        c.el.title = (v['n' + n] || 'Channel ' + n) + ': ' + (lv > 0 ? lv + ' %' : 'off');
      });
    },
  };
}

function buildFace(key) {
  switch (key) {
    case 'switch-actuator': {
      const cells = Array.from({ length: 8 }, (_, i) => { const lamp = h('i.bm-lamp'); return { el: h('span.bm-out', lamp, h('small', letter(i))), lamp }; });
      return {
        el: h('div.bm-outs', cells.map(c => c.el)),
        update(v) {
          const l = v.lamps || '';
          cells.forEach((c, i) => { const n = i + 1; c.el.hidden = v['e' + n] === false; setCls(c.lamp, 'on', l[i] === '1'); c.el.title = (v['n' + n] || 'Output ' + n) + ': ' + (l[i] === '1' ? 'on' : 'off'); });
        },
      };
    }
    case 'dimmer': return lampFace(4, false);
    case 'colour-dimmer': return lampFace(2, true);
    case 'blind-actuator': {
      const cells = Array.from({ length: 4 }, (_, i) => {
        const fill = h('i'); const val = h('span.bm-val'); const dir = h('span.bm-dir');
        return { el: h('div.bm-ch.bm-bl', h('div.bm-win', fill), h('span.bm-valrow', dir, val), h('small', letter(i))), fill, val, dir };
      });
      return {
        el: h('div.bm-chs', cells.map(c => c.el)),
        update(v) {
          cells.forEach((c, i) => {
            const n = i + 1, p = v['pos' + n] ?? 0, dr = v['dir' + n] ?? 0;
            c.el.hidden = v['e' + n] === false;
            setStyle(c.fill, 'height', p + '%'); setText(c.val, p + ' %'); setText(c.dir, dr > 0 ? '▼' : dr < 0 ? '▲' : '');
            c.el.title = (v['n' + n] || 'Blind ' + n) + ': ' + p + ' % closed' + (dr > 0 ? ', moving down' : dr < 0 ? ', moving up' : '');
          });
        },
      };
    }
    case 'coupler': {
      const row = t => { const b = h('b'); return { el: h('div', h('span', t), b), b }; };
      const r = { main: row('Main'), sub: row('Line'), filter: row('Filter'), routed: row('Routed'), blocked: row('Blocked') };
      return {
        el: h('div.bm-cp', ...Object.values(r).map(x => x.el)),
        update(v) {
          setText(r.main.b, v.mainLine || '–'); setText(r.sub.b, v.placement || '–');
          setText(r.filter.b, String(v.filterCount ?? 0)); setText(r.routed.b, String((v.routedDown ?? 0) + (v.routedUp ?? 0))); setText(r.blocked.b, String((v.blockedDown ?? 0) + (v.blockedUp ?? 0)));
        },
      };
    }
    default: return linesFace();
  }
}

/** Fallback face: the live values the floor plan would show for the device (up to three lines). */
function linesFace(max = 3) {
  const el = h('div.bm-lines'); let sig = '';
  return {
    el,
    update(v, d, live) {
      const pts = live ? pointsOf(d).slice(0, max) : [];
      const lines = pts.map(p => `${p.name || p.label}: ${p.text ?? (p.on ? 'on' : p.closed ? 'closed' : 'off')}`);
      const s = lines.join('\n'); if (s === sig) return; sig = s;
      el.replaceChildren(lines.map(t => h('div.bm-line', t)));
    },
  };
}

// ------------------------------------------------------------------ one device

/** The bits every device has: name (opens the details), address, status chip, programming button. */
function frame(d0, cls) {
  const lock = h('span.bm-lock', { hidden: true, title: 'Secure commissioned' }, icon('lock'));
  const ico = h('span.bm-ico');
  const nameB = h('b.bm-name'), addr = h('span.mono.bm-addr');
  const sel = h('button.bm-select', { type: 'button', title: 'Show the details of ' + d0.name, onclick: () => selectDevice(d0.name, { drawer: true }) }, ico, h('span.bm-id', nameB, addr));
  const typ = h('div.bm-type');
  const chip = h('span.chip.bm-chip');
  const led = h('span.led');
  const prog = h('button.bm-prog', { type: 'button', 'aria-pressed': 'false', title: 'Programming button: press it, then give the device its physical address from ETS', onclick: () => operate('Programming button', () => api.prog(d0.name)) }, led, 'Prog');
  const el = h('div.' + cls, { dataset: { dev: d0.name } });
  return {
    el, sel, lock, typ, chip, prog,
    update(d) {
      const info = productOf(d), st = statusOf(d);
      paintIcon(ico, d.name, info.icon);
      setText(nameB, d.name); setText(addr, d.unassigned ? 'no address' : d.address); setText(typ, info.title);
      chip.hidden = st.key === 'ready' && !d.programming;
      setText(chip, d.programming ? 'Programming' : st.label); chip.className = 'chip bm-chip ' + (d.programming ? 'prog' : st.cls);
      setAttr(prog, 'aria-pressed', d.programming ? 'true' : 'false'); setCls(led, 'on', !!d.programming);
      lock.hidden = !(d.secure && d.secMode); setAttr(lock, 'title', d.toolKey ? 'Secure commissioned' : 'Security mode on (factory key)');
      setCls(el, 'sel', S.selected === d.name && S.drawer);
      setCls(el, 'hl', !!S.gaSel && (d.assoc || []).some(a => a.ga === S.gaSel));
      setCls(el, 'idle', !(d.loaded || baseKey(d) === 'coupler'));
    },
  };
}

function makeModule(d0) {
  const key = baseKey(d0);
  const f = frame(d0, 'bmod'); const face = buildFace(key);
  f.el.style.setProperty('--w', TE[key] || 3);
  f.el.append(h('div.bm-top', h('span.bm-pwr', { title: 'Bus voltage present' }), f.lock), face.el, f.sel, f.typ, f.chip, f.prog);
  return { el: f.el, update(d) { f.update(d); face.update(d.loaded || key === 'coupler' ? d.view || {} : {}, d, d.loaded || key === 'coupler'); } };
}

/** A device that sits in a room: its own small card beside the board. */
function makePlate(d0) {
  const key = baseKey(d0);
  const f = frame(d0, 'bplate');
  let body, upd;
  if (key === 'push-button') {
    const keys = [1, 2, 3, 4].map(n => ({ n, btn: holdButton(d0.name, n, 'Key ' + n, 'button') }));
    body = h('div.bp-keys', keys.map(k => k.btn));
    upd = (v, d, live) => keys.forEach(k => {
      k.btn.hidden = v['e' + k.n] === false; setText(k.btn.lbl, v['n' + k.n] || 'Key ' + k.n); k.btn.title = v['n' + k.n] || 'Key ' + k.n;
      setCls(k.btn.led, 'on', !!v['s' + k.n]); setAttr(k.btn, 'disabled', !live);
    });
  } else {
    const face = linesFace(4); body = face.el; upd = face.update;
    if (key === 'info-display') {
      const prev = h('button.btn.sm', { type: 'button', onclick: () => operate('Previous screen', () => api.act(d0.name, 'prev', 0)) }, '‹ Previous');
      const next = h('button.btn.sm', { type: 'button', onclick: () => operate('Next screen', () => api.act(d0.name, 'next', 0)) }, 'Next ›');
      body = h('div', face.el, h('div.bp-row', prev, next));
    }
  }
  const line = h('span.chip.plain.bp-line');
  f.el.append(h('div.bp-head', f.sel, f.lock), line, body, h('div.bp-foot', f.chip, h('span.grow'), f.prog));
  return {
    el: f.el,
    update(d) {
      f.update(d);
      const live = d.loaded || false;
      setText(line, d.unassigned ? 'Not addressed' : 'Line ' + d.line);
      upd(live ? d.view || {} : {}, d, live);
    },
  };
}

// ------------------------------------------------------------------ the view

export function createBoard() {
  const root = h('div.view');
  const toolbar = h('div.toolbar', h('span.hint', 'Devices as they sit in a distribution board: one rail per line, the bus cable underneath.'), h('span.grow'),
    h('button.btn', { type: 'button', onclick: openAddDevice }, icon('plus'), h('span.btn-text.opt', 'Add device')));
  const encl = h('section.board-encl', { 'aria-label': 'Distribution board' });
  const unaddr = h('section.board-encl.unaddr', { 'aria-label': 'Devices without an address', hidden: true });
  const field = h('section.board-field', { 'aria-label': 'Devices outside the board' });
  const empty = h('div.empty', icon('board'), h('div', 'No devices yet.'), h('div.hint', 'Add devices with "Add device"; they appear here as modules on the rails.'));
  const scroll = h('div.board-scroll', encl, unaddr, field, empty);
  const drawerDetail = createDetail({ onClose: () => { S.drawer = false; emit('select'); } });
  const drawer = h('aside.drawer', { 'aria-label': 'Device details' }, drawerDetail.el);
  root.append(toolbar, h('div.sim-body', scroll, drawer));
  document.addEventListener('keydown', e => {
    if (e.key !== 'Escape' || drawer.hidden || root.offsetParent === null || e.defaultPrevented) return;
    if (document.querySelector('dialog[open]') || document.querySelector('[role=menu]')) return;
    const a = document.activeElement; if (a && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName)) return;
    S.drawer = false; emit('select');
  });

  let sig = '', mods = new Map(), buses = new Map();

  function rebuild(ds) {
    mods = new Map(); buses = new Map();
    const din = new Map(), plates = [], none = [];
    const iface = interfaceLine();
    if (iface) din.set(iface, []);
    for (const d of ds) {
      if (isDin(d)) { if (d.unassigned) { none.push(d); continue; } const l = lineOf(d); if (!din.has(l)) din.set(l, []); din.get(l).push(d); }
      else plates.push(d);
    }
    const lines = [...din.keys()].sort(naturalCmp);
    // the board
    encl.replaceChildren(h('div.board-title', h('b', 'Distribution board'), h('span.hint', `${ds.filter(isDin).length - none.length} devices on rails`)));
    for (const l of lines) {
      const devs = din.get(l).sort((a, b) => (baseKey(b) === 'coupler') - (baseKey(a) === 'coupler') || addrCmp(a, b));
      const n = l.split('.')[1];
      const bus = h('div.bl-bus'); buses.set(l, bus);
      const rails = []; let cur = [], used = 0;
      for (const d of devs) { const te = TE[baseKey(d)] || 3; if (cur.length && used + te > RAIL_TE) { rails.push(cur); cur = []; used = 0; } cur.push(d); used += te; }
      if (cur.length) rails.push(cur);
      const section = h('div.board-line', { style: `--ln:${lineColour(l)}` },
        h('div.bl-head', h('span.bl-sw'), h('b', n === '0' ? `Main line ${l}` : `Line ${l}`),
          l === iface ? h('span.chip.info', icon('devices'), 'ETS interface') : null),
        devs.length ? rails.map(r => h('div.bl-rail', ...r.map(d => { const m = makeModule(d); mods.set(d.name, m); return m.el; }))) : null,
        bus);
      encl.append(section);
    }
    // not addressed yet (factory state: the line is unknown)
    unaddr.hidden = !none.length;
    if (none.length) unaddr.replaceChildren(h('div.board-title', h('b', 'Not addressed yet'), h('span.hint', 'Factory state. They move onto a rail when ETS gives them an address.')),
      h('div.bl-rail', ...none.sort((a, b) => a.name.localeCompare(b.name)).map(d => { const m = makeModule(d); mods.set(d.name, m); return m.el; })));
    // outside the board
    field.hidden = !plates.length;
    if (plates.length) field.replaceChildren(h('div.board-title', h('b', 'Outside the board'), h('span.hint', 'Push buttons, displays and sensors sit in the rooms. They are on the same lines.')),
      h('div.bplates', ...plates.sort((a, b) => (a.unassigned - b.unassigned) || addrCmp(a, b) || a.name.localeCompare(b.name)).map(d => { const m = makePlate(d); mods.set(d.name, m); return m.el; })));
  }

  function paintHot() {
    const now = Date.now(), iface = interfaceLine(), hot = new Set();
    for (const e of S.telegrams.slice(-60)) {
      if (now - e.ts > 2500 || !e.tg) continue;
      const l = e.tg.origin === 'ip' ? iface : lineOfAddress(e.tg.src);
      if (l) hot.add(l);
    }
    for (const [l, b] of buses) setCls(b, 'hot', hot.has(l));
  }

  function update() {
    const ds = S.state?.devices || [];
    const iface = interfaceLine();
    const s = JSON.stringify([iface, ds.map(d => [d.name, d.product, d.unassigned ? 'u' : lineOf(d), d.address])]);
    if (s !== sig) { sig = s; rebuild(ds); }
    empty.hidden = ds.length > 0;
    encl.hidden = !ds.length;
    for (const d of ds) mods.get(d.name)?.update(d);
    paintHot();
    drawer.hidden = !(S.drawer && S.selected);
    if (!drawer.hidden) drawerDetail.update();
  }
  const live = () => { if (S.view === 'board') update(); };
  on('state', live); on('select', live); on('ga', live); on('devices', live); on('icons', live); on('telegrams', live);
  setInterval(() => { if (S.view === 'board') paintHot(); }, 1000);
  return { el: root, update, onShow: update };
}
