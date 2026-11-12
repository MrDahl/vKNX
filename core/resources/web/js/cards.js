import { h, icon, setText, setCls, setAttr, setStyle } from './util.js';
import { api } from './api.js';
import { toast } from './dialogs.js';
import { S, emit, showGroupAddress } from './bus.js';
import { SENSORS, productOf, statusOf } from './devinfo.js';
import { naturalCmp } from './util.js';
import { paintIcon } from './iconStore.js';
import { openLogicEditor } from './logicEditor.js';

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

/** "45 s", "2 min 05 s" */
export const fmtSecs = s => (s < 60 ? s + ' s' : Math.floor(s / 60) + ' min ' + String(s % 60).padStart(2, '0') + ' s');
const fmtWh = wh => (wh < 1000 ? wh.toFixed(1) + ' Wh' : (wh / 1000).toFixed(3) + ' kWh');
/** A colour that looks like white light of the given colour temperature (2000 K orange to 8000 K bluish). */
export function kelvinCss(k) {
  const t = Math.max(0, Math.min(1, (k - 2000) / 6000));
  const mix = (a, b) => Math.round(a + (b - a) * t);
  return `rgb(${mix(255, 200)}, ${mix(170, 225)}, ${mix(80, 255)})`;
}
const hex2 = n => Math.round(n).toString(16).padStart(2, '0');
/** Hue 0-360, saturation 0-1 (full brightness) -> "#rrggbb" */
function hsToHex(hDeg, sat) {
  const f = n => { const k = (n + hDeg / 60) % 6; return 255 * (1 - sat * Math.max(0, Math.min(k, 4 - k, 1))); };
  return '#' + hex2(f(5)) + hex2(f(3)) + hex2(f(1));
}
/** "#rrggbb" -> [hue 0-360, saturation 0-1] */
function hexToHs(hex) {
  const n = parseInt(hex.slice(1), 16), r = (n >> 16 & 255) / 255, g = (n >> 8 & 255) / 255, b = (n & 255) / 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min;
  let hDeg = 0;
  if (d > 0) hDeg = max === r ? ((g - b) / d + 6) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [hDeg * 60, max === 0 ? 0 : d / max];
}

/**
 * The light control screen of the info display: on/off, colour wheel (RGB) or colour temperature slider (tunable white),
 * brightness slider with − and + keys. What the user drags is never overwritten by live updates.
 */
function lightPanel(devName) {
  const act = (a, val) => api.act(devName, a, val);
  const power = h('button.lp-power', { type: 'button', 'aria-pressed': 'false', 'aria-label': 'Switch the light on or off', title: 'On / off', onclick: () => operate('On / off', () => act('lswitch', 2)) }, icon('power'));
  const dot = h('span.lp-dot');
  const wheel = h('div.lp-wheel', { role: 'slider', tabindex: 0, 'aria-label': 'Colour wheel: hue around, white in the middle' }, dot);
  const minus = h('button.lp-key', { type: 'button', 'aria-label': 'Darker', title: 'Darker (10 %)', onclick: () => operate('Brightness', () => act('lstep', -10)) }, '−');
  const plus = h('button.lp-key', { type: 'button', 'aria-label': 'Brighter', title: 'Brighter (10 %)', onclick: () => operate('Brightness', () => act('lstep', 10)) }, '+');
  const pct = h('span.lp-pct');
  const lamp = h('span.lp-lamp', icon('lamp'));
  const mid = h('div.lp-mid', minus, h('div.lp-center', wheel, lamp), plus);
  const kel = h('input.lp-kelvin', { type: 'range', min: 2700, max: 6500, step: 100, 'aria-label': 'Colour temperature in Kelvin' });
  const kelT = h('span.lp-kt');
  const kelRow = h('div.lp-row', h('span.lp-end', 'warm'), kel, h('span.lp-end', 'cold'));
  const bri = h('input.lp-bri', { type: 'range', min: 0, max: 100, step: 1, 'aria-label': 'Brightness' });
  const briRow = h('div.lp-row', h('span.lp-end', icon('lampOff')), bri, h('span.lp-end.hi', icon('lamp')));
  const el = h('div.lp', h('div.lp-top', pct, kelT, power), mid, kelRow, briRow);
  let busy = 0; const hold = () => { busy = Date.now() + 1200; };
  // colour wheel: the angle is the hue (red at the top, clockwise), the distance from the middle the saturation
  let dragging = false;
  const pick = (e, live) => {
    const r = wheel.getBoundingClientRect(), dx = e.clientX - (r.left + r.width / 2), dy = e.clientY - (r.top + r.height / 2);
    const sat = Math.min(1, Math.hypot(dx, dy) / (r.width / 2)), hue = (Math.atan2(dx, -dy) * 180 / Math.PI + 360) % 360;
    const hex = hsToHex(hue, sat);
    place(hue, sat); hold();
    const v = parseInt(hex.slice(1), 16) || 0x010101;
    if (live) act('lrgbLive', v).catch(() => {}); else operate('Colour', () => act('lrgb', v));
  };
  const place = (hue, sat) => { const a = hue * Math.PI / 180; dot.style.left = (50 + 50 * sat * Math.sin(a)) + '%'; dot.style.top = (50 - 50 * sat * Math.cos(a)) + '%'; };
  wheel.addEventListener('pointerdown', e => { dragging = true; wheel.setPointerCapture(e.pointerId); pick(e, true); });
  wheel.addEventListener('pointermove', e => { if (dragging) pick(e, true); });
  const up = e => { if (!dragging) return; dragging = false; pick(e, false); };
  wheel.addEventListener('pointerup', up); wheel.addEventListener('pointercancel', () => { dragging = false; });
  bri.addEventListener('input', () => { hold(); setText(pct, bri.value + ' %'); act('llevelLive', bri.value).catch(() => {}); });
  bri.addEventListener('change', () => { hold(); operate('Brightness', () => act('llevel', bri.value)); });
  kel.addEventListener('input', () => { hold(); setText(kelT, kel.value + ' K'); act('lkelvinLive', kel.value).catch(() => {}); });
  kel.addEventListener('change', () => { hold(); operate('Colour temperature', () => act('lkelvin', kel.value)); });
  return {
    el,
    update(v) {
      const rgb = v.lcol === 1, white = v.lcol === 2, on = !!v.lon;
      setCls(el, 'on', on); setAttr(power, 'aria-pressed', String(on));
      wheel.hidden = !rgb; lamp.hidden = rgb; kelRow.hidden = !white; kelT.hidden = !white;
      const css = rgb ? (v.lrgb || '#FFFFFF') : white ? kelvinCss(v.lk ?? 3000) : 'var(--lamp)';
      setStyle(el, '--lp-col', on ? css : 'transparent'); setStyle(lamp, 'color', on ? css : '');
      setStyle(el, '--lp-arc', (on ? 2.7 * (v.llevel ?? 0) : 0).toFixed(0) + 'deg');
      if (Date.now() < busy || dragging) return;
      setText(pct, on ? (v.llevel ?? 0) + ' %' : 'Off');
      if (+bri.value !== (v.llevel ?? 0)) bri.value = v.llevel ?? 0;
      if (white) { setAttr(kel, 'min', v.lwarm ?? 2700); setAttr(kel, 'max', v.lcold ?? 6500); if (+kel.value !== v.lk) kel.value = v.lk; setText(kelT, (v.lk ?? '') + ' K'); }
      if (rgb) { const [hue, sat] = hexToHs(v.lrgb || '#FFFFFF'); place(hue, sat); }
    },
  };
}

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
  root.append(h('div.card-head', headIco, h('div.grow', title, sub), handlers.rename ? h('button.btn.quiet.icon.card-rename', { 'aria-label': `Rename ${d.name}`, title: 'Rename device', onclick: () => handlers.rename(d.name) }, icon('pencil')) : null, menu));
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
        k.row.hidden = !en; paintIcon(k.btn.ico, d.name + '|k' + n, /^Scene/.test(v['d' + n] || '') ? 'scene' : 'button'); setText(k.btn.lbl, v['n' + n] || 'Key ' + n); k.btn.title = v['n' + n] || 'Key ' + n; setText(k.d, v['d' + n] || ''); setText(k.l, v['l' + n] || '');
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
      L.scene = holdButton(d.name, 1, 'Scene', 'scene'); L.scene.hidden = true; L.sceneInfo = h('div.hint'); L.sceneInfo.hidden = true;
      controls.append(h('button.btn.primary.press-btn', { type: 'button', onclick: () => operate('Button press', () => api.act(d.name, 'press', 0)) }, L.led, icon('button'), 'Press (on/off)'), L.s, L.scene, L.sceneInfo);
      face = v => {
        setCls(L.led, 'on', !!v.state);
        const sk = v.sceneKey || 0; L.scene.hidden = !sk; L.sceneInfo.hidden = !sk;
        if (sk) { setText(L.scene.lbl, 'Scene ' + sk); setText(L.sceneInfo, v.scene ? 'Last: ' + v.scene : 'Press for scene ' + sk + '; a long press stores it if ETS says so.'); } if (!L.s.busy()) { setText(L.v, (v.level ?? 0) + ' %'); if (+L.s.value !== v.level) L.s.value = v.level ?? 0; } };
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
      L.al = h('div.hint.alarm-line');
      state.style.flexDirection = 'column'; state.style.alignItems = 'stretch';
      state.append(blinds, L.al);
      face = (v, dev) => { const al = [v.windAlarm ? 'wind alarm' + (v.windSilent ? ' (station silent)' : '') : '', v.rainAlarm ? 'rain alarm' + (v.rainSilent ? ' (station silent)' : '') : ''].filter(Boolean); setText(L.al, al.length ? 'Active: ' + al.join(', ') : ''); L.c.forEach((c, i) => { const n = i + 1, p = v['pos' + n] ?? 0, dr = v['dir' + n] ?? 0; c.box.hidden = v['e' + n] === false; setCls(c.box, 'moving', dr !== 0); setCls(c.box, 'up', dr < 0); setText(c.ga, dev ? gaText(dev, range(3 * (n - 1) + 1, 3)) : ''); setText(c.nm, v['n' + n] || 'Blind ' + n); setStyle(c.w, 'height', p + '%'); setText(c.v, p + ' % closed'); setText(c.dir, (dr > 0 ? '↓ moving down' : dr < 0 ? '↑ moving up' : 'stopped') + (v['lock' + n] ? ' · locked by alarm' : '')); }); };
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
    case 'binary-input': {
      const pad = h('div.keypad');
      L.k = [1, 2, 3, 4].map(i => ({ row: h('div.keytile'), btn: holdButton(d.name, i, 'Input ' + i, 'contact'), latch: h('button.btn', { type: 'button', 'aria-pressed': 'false', onclick: () => operate('Contact', () => api.act(d.name, 'toggle', i)) }, 'Close the contact'),
        d: h('div.hint'), l: h('span.mono'), ga: h('div.cga') }));
      L.k.forEach(k => { k.row.append(k.btn, k.latch, k.d, k.l, k.ga); pad.append(k.row); });
      state.append(pad);
      controls.append(howTo('Each input has two ways to operate its contact: hold the large button to close it while pressed (a push button), or use the small button below it to close and open it for good (a switch or window contact). The light on a button is on while the contact is closed. What the input sends when the contact closes or opens is set in ETS.'));
      face = (v, dev) => L.k.forEach((k, i) => {
        const n = i + 1, en = v['e' + n] !== false, cl = !!v['c' + n];
        k.row.hidden = !en; setText(k.btn.lbl, v['n' + n] || 'Input ' + n); setText(k.d, v['d' + n] || '');
        setText(k.l, (cl ? 'Contact closed' : 'Contact open') + (v['l' + n] ? ' · sent: ' + v['l' + n] : ''));
        setCls(k.row, 'on', cl); setCls(k.btn.led, 'on', cl); setText(k.latch, cl ? 'Open the contact' : 'Close the contact'); setAttr(k.latch, 'aria-pressed', String(cl));
        setText(k.ga, dev ? gaText(dev, range(4 * (n - 1) + 1, 4)) : '');
      });
      break;
    }
    case 'thermostat': {
      L.t = h('div.value'); L.sp = h('div.hint'); L.mode = h('div.hint'); L.win = h('div.hint'); L.ga = h('div.cga');
      L.fill = h('i.fill'); L.bar = h('div.dslider', { role: 'meter', 'aria-valuemin': 0, 'aria-valuemax': 100, 'aria-label': 'Heating control value (display only)' }, L.fill);
      L.heat = h('span.heatflag', icon('flame')); L.ctl = h('b');
      state.style.flexDirection = 'column'; state.style.alignItems = 'stretch';
      state.append(h('div.th-main', L.t, L.sp), h('div.th-ctl', L.heat, L.ctl, L.bar), L.mode, L.win, L.ga);
      L.modes = [['Comfort', 1], ['Standby', 2], ['Economy', 3], ['Protection', 4]].map(([t, m]) => h('button.btn', { type: 'button', 'aria-pressed': 'false', title: m === 4 ? 'Building protection' : t, onclick: () => operate('Operating mode', () => api.act(d.name, 'mode', m)) }, t));
      L.down = h('button.btn.icon', { type: 'button', 'aria-label': 'Lower the setpoint by 0.5 K', title: 'Lower the setpoint by 0.5 K', onclick: () => operate('Setpoint', () => api.act(d.name, 'shift', -1)) }, icon('down'));
      L.up = h('button.btn.icon', { type: 'button', 'aria-label': 'Raise the setpoint by 0.5 K', title: 'Raise the setpoint by 0.5 K', onclick: () => operate('Setpoint', () => api.act(d.name, 'shift', 1)) }, icon('up'));
      controls.append(h('div.btn-row', ...L.modes), h('div.btn-row', h('span.hint', { style: 'align-self:center' }, 'Setpoint'), L.down, L.up),
        howTo('The keys change the operating mode and shift the setpoint by 0.5 K (at most 3 K); the operating mode object does the same from the bus. The room temperature comes from the ETS settings: a simulated room that warms up while the heating is on, a fixed value or an external sensor. The control value goes to the heating actuator through a group address.'));
      face = (v, dev) => {
        setText(L.t, v.temp == null ? '– °C' : v.temp.toFixed(1) + ' °C');
        setText(L.sp, v.sp == null ? '' : 'Setpoint ' + v.sp.toFixed(1) + ' °C' + (v.shift ? ' (shifted ' + (v.shift > 0 ? '+' : '') + v.shift.toFixed(1) + ' K)' : ''));
        const names = ['', 'Comfort', 'Standby', 'Economy', 'Building protection'];
        setText(L.mode, 'Operating mode: ' + (names[v.mode] || '–') + (v.win && v.winUsed ? ' (window open)' : ''));
        L.modes.forEach((b, i) => setAttr(b, 'aria-pressed', String(v.local === i + 1)));
        setText(L.win, v.winUsed ? 'Window: ' + (v.win ? 'open' : 'closed') : '');
        L.win.hidden = !v.winUsed;
        const c = v.ctrl ?? 0; setStyle(L.fill, 'width', c + '%'); setAttr(L.bar, 'aria-valuenow', c);
        setText(L.ctl, v.pi ? c + ' %' : (c > 0 ? 'On' : 'Off')); setCls(L.heat, 'on', c > 0);
        setText(L.ga, dev ? gaText(dev, [1, 2, 3, 4, 5, 6, 7, 8]) : '');
      };
      break;
    }
    case 'heating-actuator': {
      L.c = range(1, 6).map(() => ({ cell: h('div.valve-cell'), nm: h('span.hint'), fl: h('span.heatflag', icon('radiator')), st: h('b'), fill: h('i.fill'), ex: h('div.hint'), ga: h('span.cga') }));
      const grid = h('div.lamp-grid.dim-grid');
      L.c.forEach(c => { c.trk = h('div.dslider', { role: 'meter', 'aria-valuemin': 0, 'aria-valuemax': 100, 'aria-label': 'Control value (display only)' }, c.fill); c.cell.append(c.fl, c.nm, c.st, c.trk, c.ex, c.ga); grid.append(c.cell); });
      L.fault = h('div.hint');
      state.style.flexDirection = 'column'; state.style.alignItems = 'stretch';
      state.append(grid, L.fault);
      controls.append(howTo('The bars show the control value each valve receives; the valve opens (on/off) or pulses open for that part of every cycle (PWM). A normally closed drive is energised to open, a normally open drive is energised to close. If the control value stops coming and monitoring is on in ETS, the valve goes to its emergency position and the alarm object is sent. These values cannot be changed here.'));
      face = (v, dev) => {
        let alarm = false;
        L.c.forEach((c, i) => {
          const n = i + 1, pct = v['pct' + n] ?? 0, open = !!v['open' + n];
          c.cell.hidden = v['e' + n] === false; setText(c.nm, v['n' + n] || 'Valve ' + n);
          setCls(c.fl, 'on', open); setText(c.st, open ? 'Open' : 'Closed'); setStyle(c.fill, 'width', pct + '%'); setAttr(c.trk, 'aria-valuenow', pct);
          setText(c.ex, (v['pwm' + n] ? pct + ' % PWM' : pct > 0 ? 'On' : 'Off') + (v['nc' + n] === false ? ' · drive ' : ' · drive ') + (v['energised' + n] ? 'energised' : 'off') + (v['em' + n] ? ' · EMERGENCY POSITION' : ''));
          setText(c.ga, dev ? gaText(dev, range(3 * (n - 1) + 1, 3)) : ''); alarm = alarm || !!v['em' + n];
        });
        setText(L.fault, alarm ? 'Alarm: control value missing (object 19 sent)' : '');
      };
      break;
    }
    case 'weather-station': {
      const grid = h('div.sens-grid');
      const mk = (ic, name) => { const n = h('span.n'); const sb = h('span.sb', icon(ic)); const el = h('div.sens', h('div.l', sb, name), n); grid.append(el); return { n, sb, el }; };
      L.w = [['temp', 'Temperature', '°C'], ['wind', 'Wind speed', 'm/s'], ['lux', 'Brightness', 'lux']].map(([ic, nm, un]) => ({ ...mk(ic, nm), unit: un, ic }));
      L.rain = mk('rain', 'Rain'); L.alarm = mk('wind', 'Wind alarm');
      state.append(grid);
      controls.append(howTo('The values are sent by the station according to its ETS parameters; they cannot be changed here. The wind alarm switches on when the wind speed reaches the threshold and off again below threshold minus hysteresis. Connect the alarm to the wind alarm input of a blind actuator and the awnings retract.'));
      const put = (c, txt, unit) => { if (c.n.dataset.v !== txt) { c.n.dataset.v = txt; c.n.replaceChildren(txt, unit ? h('small', ' ' + unit) : ''); } };
      face = v => {
        L.w.forEach((c, i) => { paintIcon(c.sb, d.name + '|w' + i, c.ic); c.el.hidden = v['e' + i] === false; put(c, v['s' + i] === undefined ? '–' : String(v['s' + i]), c.unit); });
        L.rain.el.hidden = v.rainE === false; put(L.rain, v.rain ? 'Rain' : 'Dry');
        L.alarm.el.hidden = v.alarmE === false; put(L.alarm, v.alarm ? 'ALARM' : 'Off'); setCls(L.alarm.el, 'alarm', !!v.alarm); setCls(L.rain.el, 'alarm', !!v.rain);
      };
      break;
    }
    case 'presence-detector': {
      L.ico = h('span.pd-ico', icon('motion')); L.t = h('div.value'); L.sub = h('div.hint'); L.pres = h('div.hint'); L.lock = h('div.hint'); L.ga = h('div.cga');
      state.style.flexDirection = 'column'; state.style.alignItems = 'stretch';
      state.append(h('div.th-main', L.ico, L.t), L.sub, L.pres, L.lock, L.ga);
      L.move = h('button.btn.press', { type: 'button', title: 'One movement in the room', onclick: () => operate('Movement', () => api.act(d.name, 'motion', 0)) }, icon('walk'), 'Movement');
      L.stay = h('button.btn', { type: 'button', 'aria-pressed': 'false', title: 'A person stays in the room: movement all the time', onclick: () => operate('Presence', () => api.act(d.name, 'occupied', L.stay.getAttribute('aria-pressed') === 'true' ? 0 : 1)) }, icon('person'), 'Person in the room');
      L.lux = h('input', { type: 'range', min: 0, max: 1000, step: 10, value: 150, 'aria-label': 'Brightness of the room in lux', style: 'flex:1;min-width:4rem' }); L.luxT = h('span.mono', { style: 'min-width:4.2rem;text-align:right' });
      let luxBusy = 0;
      L.lux.addEventListener('input', () => { luxBusy = Date.now() + 1500; setText(L.luxT, L.lux.value + ' lux'); });
      L.lux.addEventListener('change', () => { luxBusy = Date.now() + 1500; operate('Brightness', () => api.act(d.name, 'lux', L.lux.value)); });
      controls.append(h('div.btn-row', L.move, L.stay), h('div.btn-row.slide-row', h('span.hint', { style: 'align-self:center' }, 'Room brightness'), L.lux, L.luxT),
        howTo('"Movement" is one movement in the room; "Person in the room" keeps the detector triggered until you switch it off again. The light channel sends ON at a movement when the room is darker than the threshold from ETS, and OFF when the follow-up time has passed without movement. Set the room brightness with the slider (for example above the threshold, to see that the light stays off). The presence object for heating and ventilation ignores the brightness and has its own, longer time. The lock object stops the light channel.'));
      face = (v, dev) => {
        setCls(L.ico, 'on', !!v.light); setText(L.t, v.light ? 'Light on' : 'Light off');
        setText(L.sub, v.light ? (v.occupied ? 'Movement all the time' : 'Off in ' + fmtSecs(v.left ?? 0) + ' without movement') : (v.threshold ? 'Switches on below ' + v.threshold + ' lux' : 'Switches on at any brightness'));
        L.pres.hidden = v.hvac === false; setText(L.pres, 'Presence object: ' + (v.presence ? 'occupied' + (v.occupied ? '' : ' (' + fmtSecs(v.pleft ?? 0) + ' left)') : 'not occupied'));
        L.lock.hidden = !v.locked; setText(L.lock, 'Light channel locked by the lock object');
        setAttr(L.stay, 'aria-pressed', String(!!v.occupied));
        if (Date.now() > luxBusy && document.activeElement !== L.lux) { if (v.lux != null && +L.lux.value !== v.lux) L.lux.value = v.lux; setText(L.luxT, (v.lux ?? '–') + ' lux'); }
        setText(L.ga, dev ? gaText(dev, [1, 2, 3, 4]) : '');
      };
      break;
    }
    case 'colour-dimmer': {
      L.cells = [];
      const grid = h('div.lamp-grid.dim-grid');
      for (let i = 0; i < 2; i++) {
        const b = bulb(); const nm = h('span'); const t = h('b'); const m = h('i.fill'); const ga = h('span.cga'); const col = h('span.swatch'); const ct = h('span.hint');
        const trk = h('div.dslider', { role: 'meter', 'aria-valuemin': 0, 'aria-valuemax': 100, 'aria-label': 'Brightness (display only)' }, m);
        const cell = h('div.lamp-cell', b, nm, t, trk, h('span.col-line', col, ct), ga); L.cells.push({ cell, b, nm, t, m, ga, trk, col, ct }); grid.append(cell);
      }
      state.style.flexDirection = 'column'; state.style.alignItems = 'stretch';
      state.append(grid);
      controls.append(howTo('Each channel is RGB or tunable white, as chosen in ETS. Switch and dim it like a normal dimming actuator. An RGB channel takes its colour from a 3 byte telegram (DPT 232.600: red, green, blue), for example from the Group monitor of ETS; a tunable white channel takes a colour temperature in Kelvin (DPT 7.600), limited to the range set in ETS. The small square shows the colour the lamp has.'));
      face = (v, dev) => L.cells.forEach((c, i) => {
        const n = i + 1, lv = v['level' + n] ?? 0, white = !!v['w' + n], css = white ? kelvinCss(v['k' + n] ?? 3000) : (v['rgb' + n] || '#FFFFFF');
        c.cell.hidden = v['e' + n] === false; setBulb(c.b, lv > 0, lv, d.name + '|c' + n); setStyle(c.b, 'color', lv > 0 ? css : '');
        setText(c.t, lv + ' %'); setStyle(c.m, 'width', lv + '%'); setStyle(c.m, 'background', lv > 0 ? css : '');
        setAttr(c.trk, 'aria-valuenow', lv); setStyle(c.col, 'background', css);
        setText(c.ct, white ? (v['k' + n] ?? '–') + ' K' : (v['rgb' + n] || ''));
        setText(c.ga, dev ? gaText(dev, range(9 * (n - 1) + 1, 9)) : ''); setText(c.nm, v['n' + n] || 'Channel ' + n);
      });
      break;
    }
    case 'energy-meter': {
      L.p = h('div.value'); L.e = h('div.hint'); L.a = h('div.hint'); L.al = h('div.hint.alarm-line'); L.ga = h('div.cga');
      L.loads = [1, 2, 3, 4].map(() => ({ row: h('div.load-row'), led: h('span.keyled'), nm: h('span.grow.ellipsis'), w: h('span.mono') }));
      const list = h('div.load-list'); L.loads.forEach(l => { l.row.append(l.led, l.nm, l.w); list.append(l.row); });
      state.style.flexDirection = 'column'; state.style.alignItems = 'stretch';
      state.append(h('div.th-main', h('span.pd-ico.meter', icon('flash')), L.p), L.e, L.a, L.al, list, L.ga);
      L.x = h('input', { type: 'range', min: 0, max: 3000, step: 50, value: 0, 'aria-label': 'Extra load in watt', style: 'flex:1;min-width:4rem' }); L.xT = h('span.mono', { style: 'min-width:4.4rem;text-align:right' });
      let xBusy = 0;
      L.x.addEventListener('input', () => { xBusy = Date.now() + 1500; setText(L.xT, L.x.value + ' W'); });
      L.x.addEventListener('change', () => { xBusy = Date.now() + 1500; operate('Extra load', () => api.act(d.name, 'extra', L.x.value)); });
      controls.append(h('div.btn-row.slide-row', h('span.hint', { style: 'align-self:center' }, 'Extra load'), L.x, L.xT),
        h('div.btn-row', h('button.btn', { type: 'button', onclick: () => operate('Reset', () => api.act(d.name, 'reset', 0)) }, icon('restart'), 'Reset the energy counter')),
        howTo('The meter adds up a base load, the loads it monitors and the extra load you set here. A monitored load is counted while its input object is on: connect that object in ETS to the status of a switch actuator output, and the power goes up when the lamp is switched on. Power, current and energy are sent as set in ETS; above the power limit the alarm object sends 1. The energy counter starts at 0 when vKNX starts.'));
      face = (v, dev) => {
        setText(L.p, (v.power ?? 0) + ' W'); setText(L.e, 'Energy ' + fmtWh(v.wh ?? 0)); setText(L.a, 'Current ' + (v.amp ?? 0).toFixed(2) + ' A · base load ' + (v.base ?? 0) + ' W');
        L.al.hidden = !v.limit; setText(L.al, v.limit ? (v.alarm ? 'ALARM: above the limit of ' + v.limit + ' W' : 'Limit ' + v.limit + ' W') : ''); setCls(L.al, 'alarm', !!v.alarm);
        L.loads.forEach((l, i) => { const n = i + 1; l.row.hidden = v['e' + n] !== true; setCls(l.led, 'on', !!v['on' + n]); setText(l.nm, v['n' + n] || 'Load ' + n); setText(l.w, (v['on' + n] ? '' : '(') + (v['p' + n] ?? 0) + ' W' + (v['on' + n] ? '' : ')')); });
        if (Date.now() > xBusy && document.activeElement !== L.x) { if (v.extra != null && +L.x.value !== v.extra) L.x.value = Math.min(3000, v.extra); setText(L.xT, (v.extra ?? 0) + ' W'); }
        setText(L.ga, dev ? gaText(dev, range(1, 10)) : '');
      };
      break;
    }
    case 'info-display': {
      L.hd = h('div.disp-hd'); L.name = h('b'); L.pos = h('span.mute');
      L.hd.append(L.name, L.pos);
      L.r = [1, 2, 3, 4, 5, 6].map(() => ({ row: h('div.disp-row'), l: h('span.l'), v: h('b.v') }));
      L.r.forEach(r => r.row.append(r.l, r.v));
      L.none = h('div.hint', 'No screen is enabled. Switch one on in the ETS parameters (page "Screen 1") and download.');
      L.lp = lightPanel(d.name);
      state.append(h('div.disp', L.hd, ...L.r.map(r => r.row), L.lp.el, L.none));
      L.prev = h('button.btn', { type: 'button', onclick: () => operate('Previous screen', () => api.act(d.name, 'prev', 0)) }, '‹ Previous');
      L.next = h('button.btn', { type: 'button', onclick: () => operate('Next screen', () => api.act(d.name, 'next', 0)) }, 'Next ›');
      L.nav = h('div.btn-row', L.prev, L.next);
      controls.append(L.nav,
        howTo('A values screen only shows what it receives from the bus; the values cannot be changed here. A light control screen (set in ETS: "The screen is") sends: the round button switches on and off, the slider and the − / + keys set the brightness, and the colour wheel (RGB) or the warm-to-cold slider (tunable white) sets the colour. Connect its status objects to the actuator and the screen follows what the lamp really does. Use the buttons, or the next/previous screen objects, to page through the screens.'));
      face = (v, dev) => {
        const any = !!v.cur;
        L.hd.hidden = !any; L.none.hidden = any; L.prev.disabled = L.next.disabled = !any || (v.count || 0) < 2;
        setText(L.name, v.name || ('Screen ' + v.cur)); setText(L.pos, (v.count || 0) > 1 ? v.pos + ' / ' + v.count : '');
        L.nav.hidden = (v.count || 0) < 2; // one screen only: nothing to page through
        L.lp.el.hidden = !(any && v.light); if (any && v.light) L.lp.update(v);
        L.r.forEach((r, i) => { const n = i + 1, used = any && !v.light && v['r' + n + 't'] > 0; r.row.hidden = !used; setText(r.l, v['r' + n + 'l'] || ''); setText(r.v, v['r' + n + 'v'] || '–'); });
      };
      break;
    }
    case 'logic': {
      const chip = () => { const led = h('span.keyled'); const nm = h('span.nm'); return { el: h('div.lg-io', led, nm), led, nm }; };
      L.i = range(1, 16).map(chip); L.o = range(1, 16).map(chip);
      L.inHd = h('div.lg-hd', 'Inputs'); L.outHd = h('div.lg-hd', 'Outputs');
      L.none = h('div.hint', 'No input or output is enabled. Enable them in the ETS parameters (pages "Inputs 1-8", "Outputs 1-8"), then download.');
      L.tm = range(1, 8).map(() => { const nm = h('span.nm'); const v = h('b'); return { el: h('div.disp-row', nm, v), nm, v }; });
      L.clock = h('div.lg-clock'); L.clockTxt = h('b'); L.clockSub = h('span.mute'); L.clock.append(L.clockTxt, L.clockSub);
      L.tmHd = h('div.lg-hd', 'Timers');
      state.style.flexDirection = 'column'; state.style.alignItems = 'stretch';
      state.append(L.clock, L.none, L.inHd, h('div.lg-grid', L.i.map(c => c.el)), L.outHd, h('div.lg-grid', L.o.map(c => c.el)), L.tmHd, ...L.tm.map(t => t.el));
      L.day = h('select', { 'aria-label': 'Weekday of the simulated clock', onchange: e => operate('Weekday', () => api.act(d.name, 'setDay', e.target.value)) },
        ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'].map((t, i) => h('option', { value: i }, t)));
      L.time = h('input', { type: 'time', 'aria-label': 'Time of day of the simulated clock', onchange: e => { const m = /^(\d\d):(\d\d)/.exec(e.target.value); if (m) operate('Time', () => api.act(d.name, 'setTime', +m[1] * 60 + +m[2])); } });
      L.speed = h('select', { 'aria-label': 'Speed of the simulated clock', onchange: e => operate('Clock speed', () => api.act(d.name, 'speed', e.target.value)) },
        [[1, 'Real time'], [60, '60 times faster'], [600, '600 times faster'], [3600, '3600 times faster']].map(([v, t]) => h('option', { value: v }, t)));
      controls.append(
        h('button.btn.primary', { type: 'button', onclick: () => openLogicEditor(d.name) }, icon('logic'), 'Open logic editor'),
        h('div.lg-ctl', h('label', 'Clock', L.day, L.time, L.speed), h('div.btn-row', h('button.btn', { type: 'button', onclick: () => operate('PC clock', () => api.act(d.name, 'pcClock', 0)) }, 'PC clock'),
          h('button.btn', { type: 'button', onclick: () => operate('Send time', () => api.act(d.name, 'sendTime', 0)) }, 'Send time now'))),
        howTo('The logic is drawn in the logic editor (button above). Inputs, outputs and timers appear here when they are enabled in the ETS parameters. The clock runs on the PC clock; set another weekday or time, or run it faster, to test the timers. The time and date objects are sent as set in ETS (page "Date and time").'));
      const sync = (el, val) => { if (document.activeElement !== el && el.value !== String(val)) el.value = val; };
      face = (v, dev) => {
        let any = false;
        L.i.forEach((c, k) => { const n = k + 1, e = v['ie' + n] === true; c.el.hidden = !e; any = any || e; setCls(c.led, 'on', !!v['iv' + n]); setText(c.nm, v['in' + n] || ''); });
        L.o.forEach((c, k) => { const n = k + 1, e = v['oe' + n] === true; c.el.hidden = !e; any = any || e; setCls(c.led, 'on', !!v['ov' + n]); setText(c.nm, v['on' + n] || ''); });
        L.inHd.hidden = !L.i.some(c => !c.el.hidden); L.outHd.hidden = !L.o.some(c => !c.el.hidden); L.none.hidden = any;
        let tms = 0;
        L.tm.forEach((t, k) => { const n = k + 1, e = v['te' + n] === true; t.el.hidden = !e; if (e) tms++; setText(t.nm, v['tn' + n] || ''); setText(t.v, v['tv' + n] ? 'On' : 'Off'); });
        L.tmHd.hidden = !tms;
        const days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
        setText(L.clockTxt, `${days[v.dow ?? 0]} ${v.date || ''} ${v.time || ''}`);
        setText(L.clockSub, ' ' + (v.pcClock ? 'PC clock' : (v.speed > 1 ? 'simulated, ' + v.speed + '×' : 'simulated')));
        sync(L.day, v.dow ?? 0); sync(L.speed, v.speed ?? 1);
        if (document.activeElement !== L.time && v.time) { const hm = v.time.slice(0, 5); if (L.time.dataset.hm !== hm) { L.time.dataset.hm = hm; L.time.value = hm; } }
      };
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
    if (dev.product === 'weather-station') L.w.forEach(c => { c.n.dataset.v = ''; c.n.textContent = '–'; });
    if (dev.product === 'multisensor') L.n.forEach(n => { n.dataset.v = ''; n.textContent = '–'; });
    if (dev.product === 'blind-actuator') L.c.forEach(c => setText(c.v, '–'));
  }
}
