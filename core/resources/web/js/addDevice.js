import { h, icon } from './util.js';
import { api } from './api.js';
import { S, emit, selectDevice, hooks } from './bus.js';
import { modal, toast } from './dialogs.js';

const PREFIX = { 'push-button': 'Button', 'push-rocker': 'Rocker', 'push-slider': 'Slider', 'switch-actuator': 'Switch', 'switch-actuator-4': 'Switch4',
  'switch-actuator-8': 'Switch8', dimmer: 'Dimmer', 'blind-2': 'Blind', multisensor: 'Sensor' };
const NAME_RE = /^[A-Za-z0-9_-]{1,20}$/;

function suggest(key) {
  const base = PREFIX[key] || 'Device';
  const used = new Set((S.state?.devices || []).map(d => d.name.toLowerCase()));
  for (let i = 1; i < 100; i++) if (!used.has((base + i).toLowerCase())) return base + i;
  return base;
}

/** "Add device" dialog: a catalogue with readable names, icons and a short function description. */
export function openAddDevice() {
  let chosen = null, nameTouched = false;
  const name = h('input.input#new-name', { type: 'text', maxlength: 20, autocomplete: 'off', spellcheck: false, 'aria-describedby': 'name-help' });
  name.addEventListener('input', () => { nameTouched = true; err.textContent = ''; });
  const err = h('div.error-text', { role: 'alert' });
  const addBtn = h('button.btn.primary', { disabled: true }, icon('plus'), 'Add device');
  const items = new Map();
  const grid = h('div');
  const cats = new Map();
  for (const p of S.catalog) { if (!cats.has(p.category)) cats.set(p.category, []); cats.get(p.category).push(p); }
  for (const [cat, list] of [...cats].sort((a, b) => ['Input', 'Actuator', 'Sensor'].indexOf(a[0]) - ['Input', 'Actuator', 'Sensor'].indexOf(b[0]))) {
    grid.append(h('div.cat-title', cat === 'Input' ? 'Inputs (push buttons)' : cat === 'Actuator' ? 'Actuators' : cat === 'Sensor' ? 'Sensors' : cat));
    const g = h('div.catalog');
    for (const p of list) {
      const b = h('button.cat-item', { type: 'button', 'aria-pressed': 'false', onclick: () => choose(p) },
        h('div.ico', icon(p.icon)), h('div', h('b', p.title), h('span.d', p.description)));
      items.set(p.key, b); g.append(b);
    }
    grid.append(g);
  }
  function choose(p) {
    chosen = p;
    for (const [k, b] of items) b.setAttribute('aria-pressed', String(k === p.key));
    if (!nameTouched || !name.value) { name.value = suggest(p.key); nameTouched = false; }
    addBtn.disabled = false; err.textContent = '';
  }
  async function submit(close) {
    const n = name.value.trim();
    if (!chosen) { err.textContent = 'Choose a product first.'; return; }
    if (!NAME_RE.test(n)) { err.textContent = 'The name must be 1–20 characters: letters, digits, "-" or "_" (no spaces).'; name.focus(); return; }
    if ((S.state?.devices || []).some(d => d.name.toLowerCase() === n.toLowerCase())) { err.textContent = `A device called "${n}" already exists. Choose another name.`; name.focus(); return; }
    addBtn.disabled = true;
    try {
      await api.add(chosen.key, n);
      close();
      toast(`"${n}" was added. Assign its address from ETS (switch on its programming button first).`, 'ok');
      await hooks.refresh();
      emit('devices');
      selectDevice(n, { drawer: S.view === 'simulation' });
    } catch (e) {
      err.textContent = `Could not add the device: ${e.message}`;
      addBtn.disabled = false;
    }
  }
  const dlg = modal({
    title: 'Add device', wide: true,
    body: [h('p.hint', { style: 'margin-top:0' }, 'Choose a product from the catalogue. The device is created on the virtual line; ETS then finds it through its programming button.'),
      grid,
      h('div.field', { style: 'margin-top:1.1rem' }, h('label', { for: 'new-name' }, 'Device name'), name,
        h('div.hint#name-help', 'Letters, digits, "-" and "_", up to 20 characters. Shown in the lists and on the floor plan.'), err)],
    buttons: close => { addBtn.onclick = () => submit(close); return [h('button.btn', { onclick: close }, 'Cancel'), addBtn]; },
  });
  name.addEventListener('keydown', e => { if (e.key === 'Enter') addBtn.click(); });
  dlg.querySelector('.cat-item')?.focus();
}
