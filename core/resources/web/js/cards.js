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
  const b = h('button.btn.press', { type: 'button' }, icon(iconName), h('span', label));
  let held = false;
  const release = () => { if (!held) return; held = false; b.classList.remove('down'); operate(`Key "${label}"`, () => api.act(devName, 'up', keyNo)); };
  b.addEventListener('pointerdown', e => { if (held) return; held = true; b.setPointerCapture(e.pointerId); b.classList.add('down'); operate(`Key "${label}"`, () => api.act(devName, 'down', keyNo)); });
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
      L.v = h('div.value'); state.append(L.v, h('div.hint', 'last value sent'));
      controls.append(h('button.btn.primary.press-btn', { type: 'button', onclick: () => operate('Button press', () => api.act(d.name, 'press', 0)) }, icon('button'), 'Press'));
      face = v => { setText(L.v, onOff(v.state)); };
      break;
    }
    case 'push-rocker': {
      L.v = h('div.value'); state.append(L.v, h('div.hint', 'switching state'));
      controls.append(h('div.keys', holdButton(d.name, 1, 'Upper key', 'up'), holdButton(d.name, 2, 'Lower key', 'down')), h('div.hint', 'Hold a key for a long press (relative dimming).'));
      face = v => setText(L.v, onOff(v.state));
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
      L.b = bulb(); L.v = h('div.value'); L.r = h('div.hint');
      state.append(L.b, h('div', L.v, L.r));
      face = v => { setBulb(L.b, !!v.lamp); setText(L.v, onOff(v.lamp)); setText(L.r, 'Relay ' + (v.relayClosed ? 'closed' : 'open')); };
      break;
    }
    case 'switch-actuator-4': case 'switch-actuator-8': {
      L.cells = [];
      const grid = h('div.lamp-grid');
      const n = +d.product.slice(-1);
      for (let i = 0; i < n; i++) { const b = bulb(); const t = h('b'); L.cells.push({ b, t }); grid.append(h('div.lamp-cell', b, h('span', 'Out ' + (i + 1)), t)); }
      L.sum = h('div.hint');
      state.style.flexDirection = 'column'; state.style.alignItems = 'stretch';
      state.append(grid, L.sum);
      face = v => { const l = v.lamps || ''; L.cells.forEach((c, i) => { setBulb(c.b, l[i] === '1'); setText(c.t, l[i] === '1' ? 'On' : 'Off'); }); setText(L.sum, `${[...l].filter(c => c === '1').length} of ${n} outputs on`); };
      break;
    }
    case 'dimmer': {
      L.b = bulb(); L.v = h('div.value'); L.m = h('i'); L.r = h('div.hint');
      state.append(L.b, h('div.grow', L.v, h('div.meter', L.m), L.r));
      face = v => { const lv = v.level ?? 0; setBulb(L.b, lv > 0, lv); setText(L.v, lv + ' %'); setCls(L.v, 'dim-off', lv === 0); setStyle(L.m, 'width', lv + '%'); setText(L.r, v.ramping ? 'dimming…' : lv > 0 ? 'on' : 'off'); };
      break;
    }
    case 'blind-2': {
      L.c = [1, 2].map(i => ({ w: h('i'), v: h('div.v'), dir: h('div.hint') }));
      const blinds = h('div.blinds');
      L.c.forEach((c, i) => blinds.append(h('div.blind', h('div.hint', 'Blind ' + (i + 1)), h('div.window', c.w), c.v, c.dir)));
      state.append(blinds);
      face = v => L.c.forEach((c, i) => { const p = v['pos' + (i + 1)] ?? 0, dr = v['dir' + (i + 1)] ?? 0; setStyle(c.w, 'height', p + '%'); setText(c.v, p + ' % closed'); setText(c.dir, dr > 0 ? '↓ moving down' : dr < 0 ? '↑ moving up' : 'stopped'); });
      break;
    }
    case 'multisensor': {
      L.n = SENSORS.map(s => h('span.n'));
      const grid = h('div.sens-grid');
      SENSORS.forEach((s, i) => grid.append(h('div.sens', h('div.l', icon(s.icon), s.name), L.n[i])));
      state.append(grid);
      controls.append(h('div.hint', 'Values are set in ETS. They cannot be changed here.'));
      face = v => SENSORS.forEach((s, i) => { const el = L.n[i]; const val = v['s' + i]; const txt = val === undefined ? '–' : String(val); if (el.dataset.v !== txt) { el.dataset.v = txt; el.replaceChildren(txt + ' ', h('small', s.unit)); } });
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
      const live = dev.loaded;
      root.dataset.loaded = live ? '1' : '0';
      if (live) face(v); else resetFace(dev);
      setAttr(root, 'aria-label', dev.name);
    },
  };

  function resetFace(dev) {
    // not programmed: show neutral placeholders instead of stale values
    face({ state: false, lamp: false, lamps: '0'.repeat(8), level: 0, relayClosed: false, pos1: 0, pos2: 0 });
    if (dev.product === 'multisensor') L.n.forEach(n => { n.dataset.v = ''; n.textContent = '–'; });
    if (dev.product === 'blind-2') L.c.forEach(c => setText(c.v, '–'));
  }
}
