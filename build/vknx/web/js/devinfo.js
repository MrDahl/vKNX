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
  const on = i => v['e' + i] !== false;
  switch (d.product) {
    case 'push-button': for (let i = 1; i <= 4; i++) if (on(i)) add('k' + i, 'key', { keyNo: i, sym: 'button', label: n + ' ' + (v['n' + i] || 'Key ' + i), name: v['n' + i] || 'Key ' + i }); break;
    case 'push-slider': add('btn', 'button', { name: 'Button' }); add('sl', 'slider', { label: n + ' (slider)', name: 'Slider', val: v.level ?? 0 }); break;
    case 'switch-actuator': for (let i = 1; i <= 8; i++) if (on(i)) add('l' + i, 'lamp', { on: (v.lamps || '')[i - 1] === '1', label: n + ' ' + (v['n' + i] || 'Output ' + i), name: v['n' + i] || 'Output ' + i }); break;
    case 'dimmer': for (let i = 1; i <= 4; i++) if (on(i)) add('d' + i, 'lamp', { on: (v['level' + i] ?? 0) > 0, pct: v['level' + i] ?? 0, label: n + ' ' + (v['n' + i] || 'Channel ' + i), name: v['n' + i] || 'Channel ' + i }); break;
    case 'blind-actuator': for (let i = 1; i <= 4; i++) if (on(i)) add('b' + i, 'blind', { pos: v['pos' + i] ?? 0, label: n + ' ' + (v['n' + i] || 'Blind ' + i), name: v['n' + i] || 'Blind ' + i }); break;
    case 'multisensor': SENSORS.forEach((s, i) => { if (v['e' + i] !== false) add('s' + i, 'sensor', { text: (v['s' + i] ?? '–') + ' ' + s.unit, label: n + ' ' + s.name, name: s.name, sensor: i }); }); break;
  }
  return P;
}

/** Object numbers that belong to one plan channel (falls back to all objects of the device). */
export function objectsOfPoint(d, p) {
  const all = (S.cat[d.product]?.objects || []).map(o => o.number);
  const n = +(p.k.match(/\d+$/)?.[0] || 0);
  switch (d.product) {
    case 'push-button': case 'dimmer': return [1, 2, 3, 4, 5].map(i => 5 * (n - 1) + i);
    case 'switch-actuator': return [n, 8 + n];
    case 'blind-actuator': return [3 * (n - 1) + 1, 3 * (n - 1) + 2, 3 * (n - 1) + 3];
    case 'multisensor': return [p.sensor + 1];
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
