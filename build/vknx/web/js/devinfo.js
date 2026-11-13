import { S } from './bus.js';

export const SENSORS = [
  { name: 'CO₂', unit: 'ppm', icon: 'co2' }, { name: 'Humidity', unit: '%', icon: 'hum' },
  { name: 'Illuminance', unit: 'lux', icon: 'lux' }, { name: 'Temperature', unit: '°C', icon: 'temp' },
  { name: 'Pressure', unit: 'hPa', icon: 'press' }, { name: 'Wind speed', unit: 'm/s', icon: 'wind' },
];

export const productOf = d => S.cat[d.product] || { title: d.productName, category: '', icon: 'devices', description: '', objects: [] };

/** Main status of a device from facts the backend can document. Programming mode is reported separately. */
export function statusOf(d) {
  if (d.downloadState === 'error') return { key: 'error', label: 'Download error', cls: 'danger' };
  if (d.downloadState === 'loading') return { key: 'loading', label: 'Download in progress', cls: 'info' };
  if (d.unassigned) return { key: 'noaddr', label: 'No address', cls: 'warn' };
  if (!d.loaded) return { key: 'noapp', label: 'No application', cls: 'warn' };
  return { key: 'ready', label: 'Ready', cls: 'ok' };
}

export const DOWNLOAD_TEXT = {
  empty: 'Nothing downloaded yet', loading: 'Download in progress', partial: 'Partly downloaded (some tables missing)',
  error: 'A download failed', complete: 'Complete (address, association and group object tables, application)', unknown: 'Unknown',
};

/**
 * The channels of a device that can be placed on the floor plan. Ids keep the format of earlier versions
 * ("<device>|<channel>") so saved plans keep working.
 */
export function pointsOf(d) {
  const v = d.view || {}, n = d.name, P = [];
  const add = (k, type, extra) => P.push({ id: n + '|' + k, dev: n, k, type, label: n, ...extra });
  // label on the plan: a channel that was given its own name in ETS shows just that name; a default name ("Output 1") gets the device name in front
  const lbl = (given, def) => (given && given !== def ? given : n + ' ' + def);
  const on = i => v['e' + i] !== false;
  switch (d.product.replace(/^secure-/, '')) {
    case 'push-button': for (let i = 1; i <= 4; i++) if (on(i)) add('k' + i, 'key', { keyNo: i, sym: /^Scene/.test(v['d' + i] || '') ? 'scene' : 'button', label: lbl(v['n' + i], 'Key ' + i), name: v['n' + i] || 'Key ' + i }); break;
    // one symbol: the on/off button and the slider together (older layouts also have a separate "btn" placement, which is no longer used)
    case 'push-slider': add('sl', 'slider', { name: 'Slider', on: !!v.state, val: v.level ?? 0 }); break;
    case 'switch-actuator': for (let i = 1; i <= 8; i++) if (on(i)) add('l' + i, 'lamp', { on: (v.lamps || '')[i - 1] === '1', label: lbl(v['n' + i], 'Output ' + i), name: v['n' + i] || 'Output ' + i }); break;
    case 'dimmer': for (let i = 1; i <= 4; i++) if (on(i)) add('d' + i, 'lamp', { on: (v['level' + i] ?? 0) > 0, pct: v['level' + i] ?? 0, label: lbl(v['n' + i], 'Channel ' + i), name: v['n' + i] || 'Channel ' + i }); break;
    case 'blind-actuator': for (let i = 1; i <= 4; i++) if (on(i)) add('b' + i, 'blind', { pos: v['pos' + i] ?? 0, label: lbl(v['n' + i], 'Blind ' + i), name: v['n' + i] || 'Blind ' + i }); break;
    case 'logic': add('lg', 'logic', { name: 'Logic', text: 'Logic' }); break;
    case 'info-display': add('scr', 'display', { name: 'Display', text: v.cur ? (v.name || 'Screen ' + v.cur) + (v.light ? (v.lon ? ' · ' + (v.llevel ?? 0) + ' %' : ' · off') : '') : 'No screen' }); break;
    case 'binary-input': for (let i = 1; i <= 4; i++) if (on(i)) add('c' + i, 'contact', { keyNo: i, closed: !!v['c' + i], label: lbl(v['n' + i], 'Input ' + i), name: v['n' + i] || 'Input ' + i }); break;
    case 'thermostat': add('th', 'info', { sym: 'thermostat', name: 'Thermostat', on: !!v.heat, text: (v.temp ?? '–') + ' → ' + (v.sp ?? '–') + ' °C' }); break;
    case 'heating-actuator': for (let i = 1; i <= 6; i++) if (on(i)) add('v' + i, 'info', { sym: 'radiator', on: !!v['open' + i], text: (v['pct' + i] ?? 0) + ' %', label: lbl(v['n' + i], 'Valve ' + i), name: v['n' + i] || 'Valve ' + i, objs: [3 * (i - 1) + 1, 3 * (i - 1) + 2, 3 * (i - 1) + 3] }); break;
    case 'weather-station': {
      [['temp', 'Temperature', '°C'], ['wind', 'Wind speed', 'm/s'], ['lux', 'Brightness', 'lux']].forEach(([sym, nm, un], i) => { if (on(i)) add('w' + i, 'info', { sym, name: nm, text: (v['s' + i] ?? '–') + ' ' + un, label: n + ' ' + nm.toLowerCase(), objs: [i + 1] }); });
      if (v.rainE !== false) add('rain', 'info', { sym: 'rain', name: 'Rain', on: !!v.rain, text: v.rain ? 'Rain' : 'Dry', label: n + ' rain', objs: [4] });
      if (v.alarmE !== false) add('alarm', 'info', { sym: 'wind', name: 'Wind alarm', on: !!v.alarm, text: v.alarm ? 'ALARM' : 'No alarm', label: n + ' wind alarm', objs: [5] });
      break;
    }
    case 'presence-detector': add('pd', 'info', { sym: 'motion', name: 'Presence', on: !!v.light, text: v.light ? 'Light on' : (v.presence ? 'Occupied' : 'No one'), objs: [1, 2, 3, 4] }); break;
    case 'colour-dimmer': for (let i = 1; i <= 2; i++) if (on(i)) add('c' + i, 'lamp', { on: (v['level' + i] ?? 0) > 0, pct: v['level' + i] ?? 0, col: v['w' + i] ? 'k' + (v['k' + i] ?? 3000) : (v['rgb' + i] || ''), label: lbl(v['n' + i], 'Channel ' + i), name: v['n' + i] || 'Channel ' + i, objs: [1, 2, 3, 4, 5, 6, 7, 8, 9].map(k => 9 * (i - 1) + k) }); break;
    case 'energy-meter': add('em', 'info', { sym: 'flash', name: 'Power', on: !!v.alarm, text: (v.power ?? '–') + ' W' }); break;
    case 'multisensor': SENSORS.forEach((s, i) => { if (v['e' + i] !== false) add('s' + i, 'sensor', { text: (v['s' + i] ?? '–') + ' ' + s.unit, label: n + ' ' + s.name, name: s.name, sensor: i }); }); break;
  }
  return P;
}

/** Object numbers that belong to one plan channel (falls back to all objects of the device). */
export function objectsOfPoint(d, p) {
  const all = (S.cat[d.product]?.objects || []).map(o => o.number);
  if (p.objs) return p.objs;
  const n = +(p.k.match(/\d+$/)?.[0] || 0);
  switch (d.product.replace(/^secure-/, '')) {
    case 'push-button': return [1, 2, 3, 4, 5].map(i => 5 * (n - 1) + i).concat(20 + n);
    case 'dimmer': return [1, 2, 3, 4, 5].map(i => 5 * (n - 1) + i).concat(21);
    case 'switch-actuator': return [n, 8 + n, 17];
    case 'blind-actuator': return [3 * (n - 1) + 1, 3 * (n - 1) + 2, 3 * (n - 1) + 3];
    case 'multisensor': return [p.sensor + 1];
    case 'binary-input': return [1, 2, 3, 4].map(i => 4 * (n - 1) + i);
    default: return all;
  }
}

/** Group addresses configured for the objects of a device: [{ga, obj, text, function, dpt, flags}] */
export const gasOfDevice = d => d.assoc || [];

/** Group address index built from the downloaded associations: ga -> [{dev, obj, text, function, dpt, flags, product}] */
export function gaIndex() {
  const m = new Map();
  for (const d of S.state?.devices || []) for (const a of d.assoc || []) {
    if (!m.has(a.ga)) m.set(a.ga, []);
    m.get(a.ga).push({ dev: d.name, ...a });
  }
  return m;
}

/** Small status badges for a device: address, application, download and (for secure products) the lock. */
export function badgesOf(d) {
  const B = [];
  B.push(d.unassigned ? { icon: 'alert', text: 'No address', cls: 'warn', title: 'The device has no physical address yet (15.15.255). Assign one from ETS.' }
    : { icon: 'check', text: d.address, cls: 'ok', title: 'Physical address assigned by ETS' });
  B.push(d.loaded ? { icon: 'check', text: 'Application', cls: 'ok', title: 'An application program is loaded' }
    : { icon: 'alert', text: 'No application', cls: 'warn', title: 'Download the application from ETS' });
  const dl = { complete: ['check', 'Download complete', 'ok'], partial: ['alert', 'Partial download', 'warn'], loading: ['update', 'Downloading…', 'info'], error: ['alert', 'Download error', 'danger'] }[d.downloadState];
  B.push(dl ? { icon: dl[0], text: dl[1], cls: dl[2], title: DOWNLOAD_TEXT[d.downloadState] } : { icon: 'circle', text: 'Not downloaded', cls: '', title: DOWNLOAD_TEXT.empty });
  if (d.secure) B.push(d.secMode && d.toolKey ? { icon: 'lock', text: 'Secure', cls: 'ok', title: 'Secure commissioning is done: the device only accepts configuration from ETS with the project key' }
    : d.secMode ? { icon: 'lock', text: 'Secure (factory key)', cls: 'warn', title: 'Security mode is on, but the factory key (FDSK) is still in use' }
    : { icon: 'lockOpen', text: 'Not secure yet', cls: '', title: 'Secure commissioning has not been done' });
  return B;
}
