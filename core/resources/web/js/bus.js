/** Minimal event bus + shared UI state. Views subscribe to the events they care about. */
const handlers = new Map();
export const on = (ev, fn) => { (handlers.get(ev) || handlers.set(ev, new Set()).get(ev)).add(fn); return () => handlers.get(ev).delete(fn); };
export const emit = (ev, data) => { for (const fn of handlers.get(ev) || []) { try { fn(data); } catch (e) { console.error(ev, e); } } };

import { pref } from './util.js';

export const S = {
  view: pref('view', 'installation'),   // installation | simulation | monitor
  simMode: pref('simMode', 'cards'),    // cards | plan
  state: null,                           // last /api/state
  catalog: [], cat: {},                  // product catalogue (key -> entry)
  selected: null,                        // selected device name
  drawer: false,                         // device details open in the simulation
  gaSel: null,                           // selected group address (string like "1/2/3")
  dockOpen: pref('dockOpen', false),
  dockHeight: pref('dockHeight', 240),
  telegrams: [], devlog: [], since: 0,   // buffers filled from /api/log
  filter: { text: '', device: '', service: '', origin: '', ga: '' },
  planEdit: false,
};
export function device(name) { return S.state?.devices.find(d => d.name === name); }
export function setFilter(patch) { Object.assign(S.filter, patch); emit('filter'); }
export function resetFilter() { S.filter = { text: '', device: '', service: '', origin: '', ga: '' }; emit('filter'); }

import { setPref } from './util.js';
export function navigate(view) { S.view = view; setPref('view', view); emit('nav'); }
export function selectDevice(name, opts = {}) { S.selected = name; if (opts.drawer !== undefined) S.drawer = opts.drawer; emit('select'); }
export function selectGa(ga) { S.gaSel = ga; emit('ga'); }
/** Jumps to the telegrams of one device: opens the dock in the simulation, the monitor elsewhere. */
export function showTelegrams(devName) {
  setFilter({ device: devName });
  if (S.view === 'simulation') { S.dockOpen = true; setPref('dockOpen', true); emit('dock'); } else navigate('monitor');
}
export function showGroupAddress(ga) { S.gaSel = ga; setFilter({ ga }); emit('ga'); navigate('monitor'); }
