import { h, icon, setText } from './util.js';
import { api } from './api.js';
import { S, emit, selectDevice, hooks } from './bus.js';
import { modal, toast } from './dialogs.js';

const PREFIX = { 'push-button': 'Button', 'secure-push-button': 'SecButton', 'secure-switch-actuator': 'SecSwitch', 'push-slider': 'Slider', 'switch-actuator': 'Switch', dimmer: 'Dimmer', 'blind-actuator': 'Blind',
  multisensor: 'Sensor', 'info-display': 'Display', logic: 'Logic', coupler: 'Coupler',
  'binary-input': 'Input', thermostat: 'Thermostat', 'heating-actuator': 'Heating', 'weather-station': 'Weather',
  'presence-detector': 'Presence', 'colour-dimmer': 'Colour', 'energy-meter': 'Meter' };
const MAX_NAME = 40;
// any characters are fine (spaces, other alphabets, special characters); only what would break the storage is refused (same rules as the server)
function nameProblem(n) {
  if (!n) return 'The name must not be empty.';
  if (Array.from(n).length > MAX_NAME) return `The name can have at most ${MAX_NAME} characters.`;
  if (/[\u0000-\u001f\u007f-\u009f\u2028\u2029\ufffd]/.test(n)) return 'The name must not contain line breaks or control characters.';
  if (/@\d{1,2}\.\d{1,2}!$/.test(n)) return 'The name must not end with a line suffix like @1.2!';
  return '';
}

function suggest(key) {
  const base = PREFIX[key] || 'Device';
  const used = new Set((S.state?.devices || []).map(d => d.name.toLowerCase()));
  for (let i = 1; i < 100; i++) if (!used.has((base + i).toLowerCase())) return base + i;
  return base;
}

const CATS = [['all', 'All'], ['Input', 'Inputs'], ['Actuator', 'Actuators'], ['Sensor', 'Sensors'], ['Controller', 'Controllers'], ['Display', 'Displays'], ['Logic', 'Logic and time'], ['Coupler', 'Couplers']];
const isSecure = p => p.key.startsWith('secure-');
/** The first sentence of a product description, for the list. */
const firstSentence = t => { const m = String(t || '').match(/^.*?[.!?](\s|$)/); const s = (m ? m[0] : String(t || '')).trim(); return s.length > 110 ? s.slice(0, 107) + '…' : s; };

/**
 * "Add device" dialog: search and category filter, compact product cards (name, size, Secure badge), a detail pane for the
 * selected product, and the name field with the Add button always visible at the bottom.
 */
export function openAddDevice() {
  let chosen = null, nameTouched = false, cat = 'all';
  const name = h('input.input#new-name', { type: 'text', maxlength: 40, autocomplete: 'off', spellcheck: false, placeholder: 'Device name', 'aria-label': 'Device name', 'aria-describedby': 'name-help', style: 'width:11rem' });
  const lineIn = h('input.input#new-line', { type: 'text', maxlength: 5, autocomplete: 'off', spellcheck: false, placeholder: 'automatic', 'aria-label': 'Physical line (optional)', style: 'width:7rem' });
  name.addEventListener('input', () => { nameTouched = true; err.textContent = ''; });
  const err = h('div.error-text', { role: 'alert' });
  const addBtn = h('button.btn.primary', { disabled: true }, icon('plus'), h('span', 'Add device'));
  const qty = h('input.input#new-qty', { type: 'number', min: 1, max: 20, step: 1, value: 1, 'aria-label': 'How many devices of this kind', title: 'Add several devices of the same kind at once; they are numbered', style: 'width:4.2rem' });
  qty.addEventListener('input', () => { setText(addBtn.lastChild, count() > 1 ? `Add ${count()} devices` : 'Add device'); });
  const count = () => Math.max(1, Math.min(20, Math.round(+qty.value) || 1));
  const search = h('input.input', { type: 'search', placeholder: 'Search products', 'aria-label': 'Search products', style: 'flex:1;min-width:10rem' });
  const seg = h('div.segmented', { role: 'group', 'aria-label': 'Category' });
  const catBtns = CATS.map(([k, t]) => h('button', { type: 'button', onclick: () => { cat = k; draw(); } }, t));
  seg.append(...catBtns);
  const grid = h('div.catalog.compact', { role: 'group', 'aria-label': 'Products' });
  const detail = h('aside.cat-detail', { 'aria-live': 'polite' });
  const items = new Map();

  function draw() {
    const q = search.value.trim().toLowerCase();
    catBtns.forEach((b, i) => b.setAttribute('aria-pressed', String(CATS[i][0] === cat)));
    const list = S.catalog.filter(p => (cat === 'all' || p.category === cat) && (!q || [p.title, p.name, p.description, p.order, p.category].some(t => String(t).toLowerCase().includes(q))));
    items.clear();
    grid.replaceChildren(...(list.length ? list.map(p => {
      const b = h('button.cat-item', { type: 'button', 'aria-pressed': String(chosen?.key === p.key), onclick: () => choose(p), ondblclick: () => { choose(p); name.focus(); name.select(); } },
        h('div.ico', icon(p.icon)), h('div.grow', h('b', p.title, isSecure(p) ? h('span.chip.info', { style: 'margin-left:.4rem' }, icon('lock'), 'Secure') : null), h('span.d', firstSentence(p.description))));
      items.set(p.key, b); return b;
    }) : [h('div.empty', 'No product matches. Try another word or "All".')]));
  }
  function showDetail() {
    const p = chosen;
    if (!p) { detail.replaceChildren(h('div.hint', 'Select a product to read what it does.')); return; }
    detail.replaceChildren(h('div.cat-detail-head', h('div.ico', icon(p.icon)), h('div', h('h3', p.title), h('div.mute', p.name))),
      isSecure(p) ? h('div', h('span.chip.info', icon('lock'), 'KNX Data Secure')) : null,
      h('p', p.description),
      h('dl.kv', h('dt', 'ETS order number'), h('dd.mono', p.order), h('dt', 'Group objects'), h('dd', String(p.objects?.length ?? p.objects ?? 0))),
      h('details.fold', h('summary', 'Wire to a line by hand (optional)'),
        h('p.hint', { style: 'margin:.2rem 0 .5rem' }, 'Leave empty: the device sits on the line of its physical address as soon as ETS assigns one. Enter a line such as 1.1 only to wire it by hand, for example to test a wrong address.'), lineIn));
  }
  function choose(p) {
    chosen = p;
    for (const [k, b] of items) b.setAttribute('aria-pressed', String(k === p.key));
    if (!nameTouched || !name.value) { name.value = suggest(p.key); nameTouched = false; }
    addBtn.disabled = false; err.textContent = ''; showDetail();
  }
  async function submit(close) {
    const n = name.value.trim();
    if (!chosen) { err.textContent = 'Choose a product first.'; return; }
    const bad = nameProblem(n);
    if (bad) { err.textContent = bad; name.focus(); return; }
    const used = new Set((S.state?.devices || []).map(d => d.name.toLowerCase()));
    // several at once: "Lamp1" gives Lamp1, Lamp2, Lamp3 (the number at the end is counted up, names in use are skipped)
    const names = [];
    if (count() === 1) names.push(n);
    else {
      const m = n.match(/^(.*?)(\d+)$/), base = m ? m[1] : n + ' ';
      for (let i = m ? +m[2] : 1; names.length < count() && i < 1000; i++) { const c = base + i; if (!used.has(c.toLowerCase()) && !nameProblem(c)) names.push(c); }
    }
    if (names.length < count() || names.some(c => used.has(c.toLowerCase()))) { err.textContent = count() === 1 ? `A device called "${n}" already exists. Choose another name.` : 'These names are too long or already in use. Choose a shorter name.'; name.focus(); return; }
    const ln = lineIn.value.trim();
    if (ln && !/^\d{1,2}\.\d{1,2}$/.test(ln)) { err.textContent = 'The physical line must look like 1.1 (area.line), or be empty for automatic placement.'; lineIn.focus(); return; }
    addBtn.disabled = true;
    try {
      for (const c of names) await api.add(chosen.key, c, ln);
      close();
      toast(names.length === 1 ? `"${n}" was added. Assign its address from ETS (switch on its programming button first).`
        : `${names.length} devices were added (${names[0]} to ${names.at(-1)}). Assign their addresses from ETS, one programming button at a time.`, 'ok');
      await hooks.refresh();
      emit('devices');
      selectDevice(names[0], { drawer: S.view === 'simulation' && names.length === 1 });
    } catch (e) {
      err.textContent = `Could not add the device: ${e.message}`;
      addBtn.disabled = false;
      await hooks.refresh(); emit('devices'); // some of several may have been added
    }
  }
  search.addEventListener('input', draw);
  draw(); showDetail();
  const dlg = modal({
    title: 'Add device', wide: true, cls: 'add-dlg',
    body: [h('div', { style: 'display:flex;gap:.6rem;flex-wrap:wrap;align-items:center;margin-bottom:.7rem' }, search, seg),
      h('div.add-layout', grid, detail),
      h('div.hint#name-help', { style: 'margin-top:.6rem' }, 'The device is created on the virtual line; ETS then finds it through its programming button. The name can be anything you like (spaces, any alphabet, special characters), up to 40 characters.'), err],
    buttons: close => { addBtn.onclick = () => submit(close); return [h('label', { style: 'display:flex;gap:.5rem;align-items:center;font-weight:600' }, 'Name', name), h('label.grow', { style: 'display:flex;gap:.5rem;align-items:center;font-weight:600' }, 'How many', qty), h('button.btn', { onclick: close }, 'Cancel'), addBtn]; },
  });
  name.addEventListener('keydown', e => { if (e.key === 'Enter') addBtn.click(); });
  search.focus();
}
