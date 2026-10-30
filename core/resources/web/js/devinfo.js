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
  switch (d.product) {
    case 'push-button': add('btn', 'button', { name: 'Button' }); break;
    case 'push-rocker': add('k1', 'key', { keyNo: 1, sym: 'up', label: n + ' (upper)', name: 'Upper key' }); add('k2', 'key', { keyNo: 2, sym: 'down', label: n + ' (lower)', name: 'Lower key' }); break;
    case 'push-slider': add('btn', 'button', { name: 'Button' }); add('sl', 'slider', { label: n + ' (slider)', name: 'Slider', val: v.level ?? 0 }); break;
    case 'switch-actuator': add('l1', 'lamp', { name: 'Output 1', on: !!v.lamp }); break;
    case 'switch-actuator-4': case 'switch-actuator-8': [...(v.lamps || '')].forEach((c, i) => add('l' + (i + 1), 'lamp', { on: c === '1', label: n + ' ' + (i + 1), name: 'Output ' + (i + 1) })); break;
    case 'dimmer': add('dim', 'lamp', { name: 'Light', on: v.level > 0, pct: v.level }); break;
    case 'blind-2': [1, 2].forEach(c => add('b' + c, 'blind', { pos: v['pos' + c] ?? 0, label: n + ' ' + c, name: 'Blind ' + c })); break;
    case 'multisensor': SENSORS.forEach((s, i) => add('s' + i, 'sensor', { text: (v['s' + i] ?? '–') + ' ' + s.unit, label: n + ' ' + s.name, name: s.name, sensor: i })); break;
  }
  return P;
}

/** Object numbers that belong to one plan channel (falls back to all objects of the device). */
export function objectsOfPoint(d, p) {
  const all = (S.cat[d.product]?.objects || []).map(o => o.number);
  const n = +(p.k.match(/\d+$/)?.[0] || 0);
  switch (d.product) {
    case 'switch-actuator-4': case 'switch-actuator-8': { const cnt = +d.product.slice(-1); return [n, cnt + n]; }
    case 'blind-2': return [3 * (n - 1) + 1, 3 * (n - 1) + 2, 3 * (n - 1) + 3];
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
