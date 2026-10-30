import { h, icon, setText, setCls, setAttr, setStyle } from './util.js';
import { api } from './api.js';
import { toast } from './dialogs.js';
import { S, emit } from './bus.js';
import { SENSORS, productOf, statusOf } from './devinfo.js';

/** Reports a failed operation with the concrete reason; never claims success. */
export async function operate(label, fn) {
  try { await fn(); return true; }
  catch (e) { toast(`${label} failed: ${e.message}`, 'error'); return false; }
}

const onOff = v => (v ? 'On' : 'Off');

function bulb(on) { const el = h('div.bulb'); el.append(icon('lampOff', 'off'), icon('lamp', 'on')); return el; }
function setBulb(el, on, pct) {
  setCls(el, 'on', on);
  const lv = pct == null ? (on ? 1 : 0) : pct / 100;
  setStyle(el, 'box-shadow', on ? `0 0 ${(6 + 14 * lv).toFixed(0)}px ${(1 + 3 * lv).toFixed(0)}px color-mix(in srgb, var(--lamp) ${Math.round(20 + 35 * lv)}%, transparent)` : 'none');
  const [off, onI] = el.querySelectorAll('svg');
  off.style.display = on ? 'none' : ''; onI.style.display = on ? '' : 'none';
}

/** A press-and-hold button: sends down on pointerdown and up on pointerup/cancel, even if the card is re-rendered meanwhile. */
function holdButton(devName, keyNo, label, iconName) {
  const lbl = h('span', label);
  const b = h('button.btn.press', { type: 'button' }, icon(iconName), lbl);
  b.lbl = lbl;
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
  root.append(h('div.card-head', h('div.ico', icon(info.icon)), h('div.grow', title, sub), menu));
  const state = h('div.card-state'); const controls = h('div.card-controls');
  const addr = h('span.mono'); const chip = h('span.chip'); const prog = h('span.chip.prog', h('span.led.on'), 'Programming mode');
  const foot = h('div.card-foot', addr, h('span.grow'), prog, chip);
  root.append(state, controls, foot);

  let face;
  const L = {}; // value elements
  switch (d.product) {
    case 'push-button': {
      L.k = [1, 2, 3, 4].map(i => ({ row: h('div.key-row'), btn: holdButton(d.name, i, 'Key ' + i, 'button'), d: h('div.hint'), l: h('span.mono') }));
      state.style.flexDirection = 'column'; state.style.alignItems = 'stretch';
      L.k.forEach(k => { k.row.append(k.btn, h('div.grow', k.d), k.l); state.append(k.row); });
      controls.append(h('div.hint', 'Press and hold a key for a long press. Functions and names are set in ETS.'));
      face = v => L.k.forEach((k, i) => {
        const n = i + 1, en = v['e' + n] !== false;
        k.row.hidden = !en; setText(k.btn.lbl, v['n' + n] || 'Key ' + n); setText(k.d, v['d' + n] || ''); setText(k.l, v['l' + n] || '');
        setCls(k.row, 'on', !!v['s' + n]);
      });
      break;
    }
    case 'push-slider': {
      L.v = h('div.value'); L.s = levelSlider(d.name); L.sv = h('span.mono');
      L.s.addEventListener('live', e => setText(L.v, e.detail + ' %'));
      state.append(L.v, h('div.hint', 'brightness value'));
      controls.append(h('button.btn.primary.press-btn', { type: 'button', onclick: () => operate('Button press', () => api.act(d.name, 'press', 0)) }, icon('button'), 'Press (on/off)'), L.s);
      face = v => { if (!L.s.busy()) { setText(L.v, (v.level ?? 0) + ' %'); if (+L.s.value !== v.level) L.s.value = v.level ?? 0; } };
      break;
    }
    case 'switch-actuator': {
      L.cells = [];
      const grid = h('div.lamp-grid');
      for (let i = 0; i < 8; i++) { const b = bulb(); const nm = h('span'); const t = h('b'); const cell = h('div.lamp-cell', b, nm, t); L.cells.push({ cell, b, nm, t }); grid.append(cell); }
      L.sum = h('div.hint');
      state.style.flexDirection = 'column'; state.style.alignItems = 'stretch';
      state.append(grid, L.sum);
      face = v => {
        const l = v.lamps || ''; let on = 0, en = 0;
        L.cells.forEach((c, i) => { const e = v['e' + (i + 1)] !== false; c.cell.hidden = !e; if (e) en++; const o = l[i] === '1'; if (o) on++; setBulb(c.b, o); setText(c.t, o ? 'On' : 'Off'); setText(c.nm, v['n' + (i + 1)] || 'Output ' + (i + 1)); });
        setText(L.sum, `${on} of ${en} outputs on`);
      };
      break;
    }
    case 'dimmer': {
      L.cells = [];
      const grid = h('div.lamp-grid');
      for (let i = 0; i < 4; i++) { const b = bulb(); const nm = h('span'); const t = h('b'); const m = h('i'); const cell = h('div.lamp-cell', b, nm, t, h('div.meter', m)); L.cells.push({ cell, b, nm, t, m }); grid.append(cell); }
      state.style.flexDirection = 'column'; state.style.alignItems = 'stretch';
      state.append(grid);
      face = v => L.cells.forEach((c, i) => {
        const n = i + 1, lv = v['level' + n] ?? 0;
        c.cell.hidden = v['e' + n] === false; setBulb(c.b, lv > 0, lv); setText(c.t, lv + ' %'); setStyle(c.m, 'width', lv + '%');
        setText(c.nm, v['n' + n] || 'Channel ' + n);
      });
      break;
    }
    case 'blind-actuator': {
      L.c = [1, 2, 3, 4].map(() => ({ w: h('i'), v: h('div.v'), dir: h('div.hint'), nm: h('div.hint') }));
      const blinds = h('div.blinds');
      L.c.forEach(c => { c.box = h('div.blind', c.nm, h('div.window', c.w), c.v, c.dir); blinds.append(c.box); });
      state.append(blinds);
      face = v => L.c.forEach((c, i) => { const n = i + 1, p = v['pos' + n] ?? 0, dr = v['dir' + n] ?? 0; c.box.hidden = v['e' + n] === false; setText(c.nm, v['n' + n] || 'Blind ' + n); setStyle(c.w, 'height', p + '%'); setText(c.v, p + ' % closed'); setText(c.dir, dr > 0 ? '↓ moving down' : dr < 0 ? '↑ moving up' : 'stopped'); });
      break;
    }
    case 'multisensor': {
      L.n = SENSORS.map(s => h('span.n'));
      const grid = h('div.sens-grid');
      L.cellEl = SENSORS.map((s, i) => { const c = h('div.sens', h('div.l', icon(s.icon), s.name), L.n[i]); grid.append(c); return c; });
      state.append(grid);
      controls.append(h('div.hint', 'Values are set in ETS. They cannot be changed here.'));
      face = v => SENSORS.forEach((s, i) => { const el = L.n[i]; L.cellEl[i].hidden = v['e' + i] === false; const val = v['s' + i]; const txt = val === undefined ? '–' : String(val); if (el.dataset.v !== txt) { el.dataset.v = txt; el.replaceChildren(txt + ' ', h('small', s.unit)); } });
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
      controls.append(h('div.hint', 'Everything shown here comes from the ETS download. Group telegrams in “filter” mode only pass if the group address is in the filter table.'));
      face = v => {
        const hi = (a, b) => (a === b ? a : a + ' / ' + b + ' (14-31)');
        const roleText = v.role === 'area' ? 'Area coupler: main line ↔ backbone' : v.role === 'line' ? 'Line coupler: sub line ↔ main line' : 'Not placed: needs an address like 1.2.0 (line) or 1.0.0 (area) to join the lines';
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
      setText(addr, dev.unassigned ? 'No address' : dev.address);
      setText(chip, st.label); chip.className = 'chip ' + st.cls;
      prog.hidden = !dev.programming;
      setCls(root, 'selected', S.selected === dev.name && S.drawer);
      const live = dev.loaded || dev.product === 'coupler'; // a coupler routes even before a download (factory state)
      root.dataset.loaded = live ? '1' : '0';
      if (live) face(v); else resetFace(dev);
      setAttr(root, 'aria-label', dev.name);
    },
  };

  function resetFace(dev) {
    // not programmed: show neutral placeholders instead of stale values
    face({ lamps: '0'.repeat(8) });
    if (dev.product === 'multisensor') L.n.forEach(n => { n.dataset.v = ''; n.textContent = '–'; });
    if (dev.product === 'blind-actuator') L.c.forEach(c => setText(c.v, '–'));
  }
}
