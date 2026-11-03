import { MDI } from './icons.js';
import { icon } from './util.js';
import { S, emit } from './bus.js';
import { api } from './api.js';
import { SENSORS } from './devinfo.js';

/**
 * The icons the user chose (Material Design Icons) for devices and channels. Stored in icons.json as
 * { v: 1, icons: { "<id>": { n: "ceiling-light", p: "<svg path>", o: "<svg path of the off variant, optional>" } } }.
 * The path is stored with the name, so drawing an icon never needs the big catalogue. Ids are "<device>" for the
 * icon of a whole device and "<device>|<channel>" for a channel (the same ids the floor plan uses).
 */
export async function loadIcons() {
  try { S.icons = (await api.icons()).icons || {}; } catch (e) { S.icons = {}; }
  emit('icons');
}

const norm = def => (typeof def === 'string' ? { on: def } : def);

/** The svg path for an id: the user's choice or the built-in default. on=false picks the off variant where there is one. */
export function resolvePath(id, def, on = true) {
  const e = S.icons?.[id], d = norm(def);
  if (e) return on === false && e.o ? e.o : e.p;
  return MDI[on === false && d.off ? d.off : d.on] || MDI.info;
}

/** Puts the icon for `id` into `box` (created on first use, rewritten only when the path changed). */
export function paintIcon(box, id, def, on = true) {
  let svg = box.firstElementChild;
  if (!svg || svg.tagName.toLowerCase() !== 'svg') { box.replaceChildren(icon('info')); svg = box.firstElementChild; }
  const path = svg.firstElementChild, d = resolvePath(id, def, on);
  if (path.getAttribute('d') !== d) path.setAttribute('d', d);
  const dim = on === false && !!S.icons?.[id] && !S.icons[id].o; // a chosen icon without an off variant is only dimmed
  if (box.classList.contains('dim-off') !== dim) box.classList.toggle('dim-off', dim);
  return box;
}

export const hasCustomIcon = id => !!S.icons?.[id];

let saving = Promise.resolve();
/** Stores (or, with entry = null, forgets) the icon of one id and tells every view to redraw. */
export function setIcon(id, entry) {
  if (entry) S.icons[id] = entry; else delete S.icons[id];
  emit('icons');
  saving = saving.then(() => api.saveIcons({ v: 1, icons: S.icons })).catch(() => {});
  return saving;
}
export function forgetIconsOf(dev) {
  let any = false;
  for (const k of Object.keys(S.icons || {})) if (k === dev || k.startsWith(dev + '|')) { delete S.icons[k]; any = true; }
  if (any) { emit('icons'); saving = saving.then(() => api.saveIcons({ v: 1, icons: S.icons })).catch(() => {}); }
}

/** Built-in icon and picker kind of a floor plan channel (see pointsOf in devinfo.js). */
export function pointDefault(p) {
  switch (p.type) {
    case 'lamp': return { on: 'lamp', off: 'lampOff' };
    case 'key': return p.sym || 'button';
    case 'button': return 'button';
    case 'slider': return 'slider';
    case 'blind': return 'blind';
    case 'sensor': return SENSORS[p.sensor].icon;
    default: return 'info';
  }
}
export const pointKind = p => ({ lamp: 'light', key: 'key', button: 'key', slider: 'slider', blind: 'blind', sensor: 'sensor' }[p.type] || 'device');
