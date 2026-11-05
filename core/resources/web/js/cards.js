import { h, icon, setText, setCls, setAttr, setStyle } from './util.js';
import { api } from './api.js';
import { toast } from './dialogs.js';
import { S, emit, showGroupAddress } from './bus.js';
import { SENSORS, productOf, statusOf } from './devinfo.js';
import { naturalCmp } from './util.js';
import { paintIcon } from './iconStore.js';

/** Reports a failed operation with the concrete reason; never claims success. */
export async function operate(label, fn) {
  try { await fn(); return true; }
  catch (e) { toast(`${label} failed: ${e.message}`, 'error'); return false; }
}

const onOff = v => (v ? 'On' : 'Off');

/** Group addresses ETS downloaded for the given object numbers of a device, as short text ("1/1/1, 1/1/2 +1"). */
function gaText(dev, objs) {
  const set = new Set();
  for (const a of dev.assoc || []) if (objs.includes(a.obj)) set.add(a.ga);
  const l = [...set].sort(naturalCmp);
  return l.length ? l.slice(0, 2).join(', ') + (l.length > 2 ? ` +${l.length - 2}` : '') : '';
}
/** Long usage hints live in a small fold-out so the card stays about the device, not about instructions. */
const howTo = text => h('details.howto', h('summary', 'How to operate'), h('div.hint', text));
const range = (from, n) => Array.from({ length: n }, (_, i) => from + i);

const LAMP = { on: 'lamp', off: 'lampOff' };
function bulb() { const el = h('div.bulb'); el.append(icon('lampOff')); return el; }
/** id: the icon id of this output ("<device>|l3"): the user's own icon or the built-in lamp */
function setBulb(el, on, pct, id) {
  setCls(el, 'on', on);
  const lv = pct == null ? (on ? 1 : 0) : pct / 100;
  setStyle(el, 'box-shadow', on ? `0 0 ${(6 + 14 * lv).toFixed(0)}px ${(1 + 3 * lv).toFixed(0)}px color-mix(in srgb, var(--lamp) ${Math.round(20 + 35 * lv)}%, transparent)` : 'none');
  paintIcon(el, id, LAMP, on);
}

/** A press-and-hold button: sends down on pointerdown and up on pointerup/cancel, even if the card is re-rendered meanwhile. */
function holdButton(devName, keyNo, label, iconName) {
  const lbl = h('span', label);
  const led = h('span.keyled');
  const ico = h('span.kico', icon(iconName || 'button'));
  const b = h('button.btn.press.keycap', { type: 'button' }, led, ico, lbl);
  b.lbl = lbl; b.led = led; b.ico = ico;
  let held = false;
  const release = () => { if (!held) return; held = false; b.classList.remove('down'); operate(`Key "${lbl.textContent}"`, () => api.act(devName, 'up', keyNo)); };
  b.addEventListener('pointerdown', e => { if (held) return; held = true; b.setPointerCapture(e.pointerId); b.classList.add('down'); operate(`Key "${lbl.textContent}"`, () => api.act(devName, 'down', keyNo)); });
  b.addEventListener('pointerup', release);
  b.addEventListener('pointercancel', release);
  b.addEventListener('lostpointercapture', release);
  b.addEventListener('keydown', e => { if ((e.key === ' ' || e.key === 'Enter') && !e.repeat) { e.preventDefault(); if (!held) { held = true; b.classList.add('down'); api.act(devName, 'down', keyNo).catch(() => {}); } } });
  b.addEventListener('keyup', e => { if ((e.key === ' ' || e.key === 'Enter') && held) { e.preventDefault(); release(); } });
  return b;
}

/** A slider that is never overwritten while the user holds it (or just released it). */
function levelSlider(devName) {
  const input = h('input', { type: 'range', min: 0, max: 100, step: 1, value: 0, 'aria-label': 'Brightness slider' });
  let active = false, until = 0;
  input.addEventListener('pointerdown', () => { active = true; });
  const end = () => { active = false; until = Date.now() + 500; };
  input.addEventListener('pointerup', end); input.addEventListener('pointercancel', end);
  input.addEventListener('keydown', () => { active = true; });
  input.addEventListener('keyup', () => { active = false; until = Date.now() + 500; });
  input.addEventListener('input', () => { input.dispatchEvent(new CustomEvent('live', { detail: +input.value })); api.act(devName, 'sliderLive', input.value).catch(() => {}); });
  input.addEventListener('change', () => operate('Slider', () => api.act(devName, 'slider', input.value)));
  input.busy = () => active || Date.now() < until || document.activeElement === input;
  return input;
}

/**
 * Builds the card of one device. The DOM is created once; update(d) only writes values that changed,
 * so keyboard focus, sliders being dragged and held keys are never disturbed by live updates.
 */
export function createCard(d, handlers) {
  const info = productOf(d);
  const root = h('article.card', { dataset: { dev: d.name } });
  const title = h('h3.ellipsis', d.name);
  const sub = h('div.sub.ellipsis', info.title);
  const menu = handlers.menu(d.name);
  const headIco = h('div.ico', icon(info.icon));
  root.append(h('div.card-head', headIco, h('div.grow', title, sub), menu));
  const state = h('div.card-state'); const controls = h('div.card-controls');
  const addr = h('span.mono'); const chip = h('span.chip'); const prog = h('span.chip.prog', h('span.led.on'), 'Programming mode');
  const lockI = h('span.lock-i', { hidden: true }, icon('lock'));
  const foot = h('div.card-foot', addr, lockI, h('span.grow'), prog, chip);
  const gas = h('details.card-gas', { hidden: true }); const gasSum = h('summary'); const gasBody = h('div.gas-body'); gas.append(gasSum, gasBody); let gasSig = '';
  root.append(state, controls, gas, foot);
  function updateGas(dev) {
    const list = (dev.assoc || []).slice().sort((a, b) => naturalCmp(a.ga, b.ga) || a.obj - b.obj);
    const sig = JSON.stringify(list.map(a => [a.ga, a.obj]));
    gas.hidden = !list.length;
    if (sig === gasSig) return; gasSig = sig;
    setText(gasSum, `Group addresses (${new Set(list.map(a => a.ga)).size})`);
    gasBody.replaceChildren(...list.map(a => h('div.ga-line', h('button.ga-chip', { title: `Show group address ${a.ga}`, onclick: () => showGroupAddress(a.ga) }, a.ga), h('span.mute', `${a.text}${a.dpt ? ' · DPT ' + a.dpt : ''}`))));
  }

  let face;
  const L = {}; // value elements
  switch (d.product.replace(/^secure-/, '')) {
    case 'push-button': {
      const pad = h('div.keypad');
      L.k = [1, 2, 3, 4].map(i => ({ row: h('div.keytile'), btn: holdButton(d.name, i, 'Key ' + i, 'button'), d: h('div.hint'), l: h('span.mono'), ga: h('div.cga') }));
      L.k.forEach(k => { k.row.append(k.btn, k.d, k.l, k.ga); pad.append(k.row); });
      state.append(pad);
      controls.append(howTo('Press and hold a key for a long press. The small light on a key is its status LED. A key set to Scene calls its scene when pressed; if ETS lets the long press store the scene, a short press calls it and a long press stores it. Functions and names are set in ETS.'));
      face = (v, dev) => L.k.forEach((k, i) => {
        const n = i + 1, en = v['e' + n] !== false;
        k.row.hidden = !en; paintIcon(k.btn.ico, d.name + '|k' + n, /^Scene/.test(v['d' + n] || '') ? 'scene' : 'button'); setText(k.btn.lbl, v['n' + n] || 'Key ' + n); setText(k.d, v['d' + n] || ''); setText(k.l, v['l' + n] || '');
        setCls(k.row, 'on', !!v['s' + n]); setCls(k.btn.led, 'on', !!v['s' + n]);
        setText(k.ga, dev ? gaText(dev, [...range(5 * (n - 1) + 1, 5), 20 + n]) : '');
      });
      break;
    }
    case 'push-slider': {
      L.v = h('div.value'); L.s = levelSlider(d.name); L.sv = h('span.mono');
      L.s.addEventListener('live', e => setText(L.v, e.detail + ' %'));
      state.append(L.v, h('div.hint', 'brightness value'));
      L.led = h('span.keyled');
      controls.append(h('button.btn.primary.press-btn', { type: 'button', onclick: () => operate('Button press', () => api.act(d.name, 'press', 0)) }, L.led, icon('button'), 'Press (on/off)'), L.s);
      face = v => { setCls(L.led, 'on', !!v.state); if (!L.s.busy()) { setText(L.v, (v.level ?? 0) + ' %'); if (+L.s.value !== v.level) L.s.value = v.level ?? 0; } };
      break;
    }
    case 'switch-actuator': {
      L.cells = [];
      const grid = h('div.lamp-grid');
      for (let i = 0; i < 8; i++) { const b = bulb(); const nm = h('span'); const t = h('b'); const ga = h('span.cga'); const cell = h('div.lamp-cell', b, nm, t, ga); L.cells.push({ cell, b, nm, t, ga }); grid.append(cell); }
      L.sum = h('div.hint');
      state.style.flexDirection = 'column'; state.style.alignItems = 'stretch';
      state.append(grid, L.sum);
      face = (v, dev) => {
        const l = v.lamps || ''; let on = 0, en = 0;
        L.cells.forEach((c, i) => { setText(c.ga, dev ? gaText(dev, [i + 1, 9 + i]) : ''); const e = v['e' + (i + 1)] !== false; c.cell.hidden = !e; if (e) en++; const o = l[i] === '1'; if (o) on++; setBulb(c.b, o, null, d.name + '|l' + (i + 1)); setText(c.t, o ? 'On' : 'Off'); setText(c.nm, v['n' + (i + 1)] || 'Output ' + (i + 1)); });
        setText(L.sum, `${on} of ${en} outputs on` + (v.scene ? ' · ' + v.scene : ''));
      };
      break;
    }
    case 'dimmer': {
      L.cells = [];
      const grid = h('div.lamp-grid.dim-grid');
      for (let i = 0; i < 4; i++) {
        const b = bulb(); const nm = h('span'); const t = h('b'); const m = h('i.fill'); const ga = h('span.cga');
        const trk = h('div.dslider', { role: 'meter', 'aria-valuemin': 0, 'aria-valuemax': 100, 'aria-label': 'Brightness (display only)' }, m);
        const cell = h('div.lamp-cell', b, nm, t, trk, ga); L.cells.push({ cell, b, nm, t, m, ga, trk }); grid.append(cell);
      }
      L.scene = h('div.hint');
      state.style.flexDirection = 'column'; state.style.alignItems = 'stretch';
      state.append(grid, L.scene);
      controls.append(howTo('The bars show the actual brightness of each channel; they cannot be dragged. Dim with the push buttons or a slider device (relative or absolute value from ETS). A scene telegram sets every channel that has the scene number in one of its scene slots; a store telegram saves the current brightness in those slots.'));
      face = (v, dev) => { setText(L.scene, v.scene || ''); L.cells.forEach((c, i) => {
        const n = i + 1, lv = v['level' + n] ?? 0;
        c.cell.hidden = v['e' + n] === false; setBulb(c.b, lv > 0, lv, d.name + '|d' + n); setText(c.t, lv + ' %'); setStyle(c.m, 'width', lv + '%');
        setAttr(c.trk, 'aria-valuenow', lv); setText(c.ga, dev ? gaText(dev, range(5 * (n - 1) + 1, 5)) : '');
        setText(c.nm, v['n' + n] || 'Channel ' + n);
      }); };
      break;
    }
    case 'blind-actuator': {
      L.c = [1, 2, 3, 4].map(() => ({ w: h('i'), v: h('div.v'), dir: h('div.hint'), nm: h('div.hint'), ga: h('div.cga') }));
      const blinds = h('div.blinds');
      L.c.forEach(c => { c.box = h('div.blind', c.nm, h('div.window', c.w), c.v, c.dir, c.ga); blinds.append(c.box); });
      state.append(blinds);
      face = (v, dev) => L.c.forEach((c, i) => { const n = i + 1, p = v['pos' + n] ?? 0, dr = v['dir' + n] ?? 0; c.box.hidden = v['e' + n] === false; setCls(c.box, 'moving', dr !== 0); setCls(c.box, 'up', dr < 0); setText(c.ga, dev ? gaText(dev, range(3 * (n - 1) + 1, 3)) : ''); setText(c.nm, v['n' + n] || 'Blind ' + n); setStyle(c.w, 'height', p + '%'); setText(c.v, p + ' % closed'); setText(c.dir, dr > 0 ? '↓ moving down' : dr < 0 ? '↑ moving up' : 'stopped'); });
      break;
    }
    case 'multisensor': {
      L.n = SENSORS.map(s => h('span.n'));
      const grid = h('div.sens-grid');
      L.sib = SENSORS.map(s => h('span.sb', icon(s.icon)));
      L.cellEl = SENSORS.map((s, i) => { const c = h('div.sens', h('div.l', L.sib[i], s.name), L.n[i]); grid.append(c); return c; });
      state.append(grid);
      controls.append(howTo('The values are sent by the sensor according to its ETS parameters. They cannot be changed here.'));
      face = v => SENSORS.forEach((s, i) => { const el = L.n[i]; paintIcon(L.sib[i], d.name + '|s' + i, s.icon); L.cellEl[i].hidden = v['e' + i] === false; const val = v['s' + i]; const txt = val === undefined ? '–' : String(val); if (el.dataset.v !== txt) { el.dataset.v = txt; el.replaceChildren(txt + ' ', h('small', s.unit)); } });
      break;
    }
    case 'coupler': {
      L.rows = [['groupMainSub', 'Group, main → sub'], ['groupSubMain', 'Group, sub → main'],
        ['physMainSub', 'Individual, main → sub'], ['physSubMain', 'Individual, sub → main']].map(([k, t]) => ({ k, t, v: h('b') }));
      L.role = h('div.hint'); L.cnt = h('div.hint'); L.filter = h('div.hint'); L.block = h('div.hint');
      const grid = h('div.cp-grid');
      L.rows.forEach(r => grid.append(h('div.cp-row', h('span', r.t), r.v)));
      state.style.flexDirection = 'column'; state.style.alignItems = 'stretch';
      state.append(L.role, grid, L.cnt, L.filter, L.block);
      controls.append(howTo('Everything shown here comes from the ETS download. Group telegrams in “filter” mode only pass if the group address is in the filter table.'));
      face = v => {
        const hi = (a, b) => (a === b ? a : a + ' / ' + b + ' (14-31)');
        const roleText = (v.role === 'area' ? `Area coupler: main line ${v.placement} ↔ backbone` : v.role === 'line' ? `Line coupler: line ${v.placement} ↔ main line ${v.mainLine}` : 'Not wired to a line') + (v.addressState === 'mismatch' ? ' · ADDRESS DOES NOT MATCH its line (should be ' + v.placement + '.0)' : '');
        setText(L.role, roleText + (v.loaded ? '' : ' · no download yet'));
        for (const r of L.rows) {
          const val = r.k === 'groupMainSub' ? hi(v.groupMainSub, v.groupMainSubHigh) : r.k === 'groupSubMain' ? hi(v.groupSubMain, v.groupSubMainHigh) : v[r.k];
          setText(r.v, val ?? '–');
        }
        setText(L.cnt, `Routed ${v.routedDown ?? 0} down, ${v.routedUp ?? 0} up · blocked ${v.blockedDown ?? 0} down, ${v.blockedUp ?? 0} up`);
        const f = v.filter || [];
        setText(L.filter, `Filter table: ${v.filterCount ?? 0} group address(es)` + (f.length ? ' – ' + f.slice(0, 12).join(', ') + (v.filterCount > 12 ? ' …' : '') : ''));
        setText(L.block, v.lastBlocked ? 'Last blocked: ' + v.lastBlocked : '');
      };
      break;
    }
    default: face = () => {};
  }

  return {
    el: root,
    update(dev) {
      const v = dev.view || {};
      const st = statusOf(dev);
      paintIcon(headIco, dev.name, info.icon);
      addr.hidden = dev.unassigned; setText(addr, dev.unassigned ? '' : dev.address);
      setText(chip, st.label); chip.className = 'chip ' + st.cls;
      prog.hidden = !dev.programming;
      setCls(root, 'selected', S.selected === dev.name && S.drawer);
      const live = dev.loaded || dev.product === 'coupler'; // a coupler routes even before a download (factory state)
      root.dataset.loaded = live ? '1' : '0';
      if (live) face(v, dev); else resetFace(dev);
      lockI.hidden = !(dev.secure && dev.secMode); lockI.title = dev.toolKey ? 'Secure commissioned' : 'Security mode on (factory key)';
      updateGas(dev);
      setAttr(root, 'aria-label', dev.name);
    },
  };

  function resetFace(dev) {
    // not programmed: show neutral placeholders instead of stale values
    face({ lamps: '0'.repeat(8) }, null);
    if (dev.product === 'multisensor') L.n.forEach(n => { n.dataset.v = ''; n.textContent = '–'; });
    if (dev.product === 'blind-actuator') L.c.forEach(c => setText(c.v, '–'));
  }
}
