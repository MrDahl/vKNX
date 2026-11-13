import { h, icon, setText, setCls, setStyle, setAttr, syncKeyed, clamp, debounce, naturalCmp } from '../util.js';
import { api } from '../api.js';
import { S, on, emit, device, selectDevice, showGroupAddress } from '../bus.js';
import { pointsOf, objectsOfPoint, SENSORS } from '../devinfo.js';
import { operate, kelvinCss, createCard } from '../cards.js';
import { confirmDialog, toast, menuButton, modal } from '../dialogs.js';
import { paintIcon, setIcon, hasCustomIcon, pointDefault, pointKind } from '../iconStore.js';
import { pickIcon } from '../iconPicker.js';

const MIN_Z = 20, MAX_Z = 300;

/**
 * Floor plan: "Operate" mode (maximum space, inputs work, no editing tools) and "Edit" mode (channel list, move symbols,
 * properties). Placements are stored in plan-layout.json as { items: [{id:"<device>|<channel>", x, y, label?, f?}], zoom, size,
 * floors?: [{n, name, zoom?}], floor? }. A plan can have several floors: each has its own image (floor n, 0 to 7) and its own
 * zoom; an item is on floor f (0 if absent, which is also what a layout from before floors existed means).
 */
export function createPlan() {
  const root = h('div.view', { hidden: true });
  let layout = { items: [], zoom: 100, size: 1 };
  let loaded = false, hasImg = false, planKnown = null, sel = null, dragging = null, panning = null;
  const selSet = new Set(); // all selected symbols (edit mode); sel is the one whose properties are shown
  const undoStack = []; let lastNudge = 0;
  /** Remembers where the symbols are, before something changes them (place, move, align, remove). */
  function pushUndo() { undoStack.push(JSON.stringify(layout.items)); if (undoStack.length > 40) undoStack.shift(); refreshTools(); }
  function undo() {
    if (!undoStack.length) return;
    layout.items = JSON.parse(undoStack.pop()); selSet.clear(); sel = null; save(); update();
  }
  const MAX_FLOORS = 8;
  let cur = 0; // the floor shown (its number n, which is also the number of its image)
  const floors = () => (layout.floors && layout.floors.length ? layout.floors : [{ n: 0, name: 'Floor 1' }]);
  const floorObj = n => floors().find(f => f.n === n);
  const onFloor = i => (i.f || 0) === cur;

  const save = debounce(async () => {
    const fl = floors().map(f => (f.n === cur ? { ...f, zoom: layout.zoom } : f));
    const out = { items: layout.items, zoom: fl.find(f => f.n === 0)?.zoom ?? layout.zoom, size: layout.size };
    if (layout.floors && layout.floors.length) { out.floors = fl; out.floor = cur; }
    try { await api.saveLayout(out); } catch (e) { toast(`The floor plan layout could not be saved: ${e.message}`, 'error'); }
  }, 300);

  // ----- toolbar -----
  const seg = h('div.segmented', { role: 'group', 'aria-label': 'Floor plan mode' });
  const bOp = h('button', { type: 'button', onclick: () => setEdit(false), title: 'Operate the installation: press buttons, move sliders' }, icon('button'), 'Operate');
  const bEd = h('button', { type: 'button', onclick: () => setEdit(true), title: 'Place and arrange symbols' }, icon('edit'), 'Edit plan');
  seg.append(bOp, bEd);
  const zVal = h('span.mono', { style: 'min-width:3.2rem;text-align:right' });
  const zRange = h('input', { type: 'range', min: MIN_Z, max: MAX_Z, step: 5, 'aria-label': 'Zoom' });
  zRange.addEventListener('input', () => setZoom(+zRange.value));
  const sRange = h('input', { type: 'range', min: 60, max: 200, step: 10, 'aria-label': 'Symbol size', style: 'width:90px' });
  sRange.addEventListener('input', () => { layout.size = +sRange.value / 100; applySize(); save(); });
  const file = h('input', { type: 'file', accept: '.png,.jpg,.jpeg,.svg,.webp,.gif,image/*', hidden: true });
  file.addEventListener('change', upload);
  const symBox = h('span.zoom-box', h('span.hint', 'Symbol size'), sRange);
  const planMenu = menuButton({ label: 'Plan settings', text: 'Plan settings', items: () => [
    { label: hasImg ? 'Replace plan image…' : 'Upload plan image…', icon: 'upload', onclick: () => file.click() },
    ...(hasImg ? [{ label: 'Remove plan image…', icon: 'trash', danger: true, onclick: removePlanImage }] : []),
    'hr',
    ...(floors().length < MAX_FLOORS ? [{ label: 'Add a floor…', icon: 'plus', onclick: addFloor }] : []),
    { label: 'Rename this floor…', icon: 'pencil', onclick: renameFloor },
    ...(floors().length > 1 ? [{ label: 'Remove this floor…', icon: 'trash', danger: true, onclick: removeFloor }] : [])] });
  const settingsBox = h('span.plan-settings', planMenu);
  const bUndo = h('button.btn', { type: 'button', disabled: true, title: 'Undo the last change to the symbols (Ctrl+Z)', onclick: undo }, icon('restart'), h('span.btn-text.opt', 'Undo'));
  const floorTabs = h('div.segmented.floor-tabs', { role: 'group', 'aria-label': 'Floor', hidden: true });
  const toolbar = h('div.toolbar', seg, floorTabs,
    h('button.btn', { onclick: fit, title: 'Fit the whole plan into the window' }, icon('fit'), h('span.btn-text.opt', 'Fit to window')),
    h('span.zoom-box', h('button.btn.icon', { 'aria-label': 'Zoom out', title: 'Zoom out', onclick: () => setZoom(layout.zoom - 15) }, icon('zoomOut')), zRange, h('button.btn.icon', { 'aria-label': 'Zoom in', title: 'Zoom in', onclick: () => setZoom(layout.zoom + 15) }, icon('zoomIn')), zVal),
    symBox, bUndo, h('span.grow'), settingsBox, file);

  // ----- canvas -----
  const img = h('img', { alt: 'Floor plan', draggable: false });
  const emptyMsg = h('div', { style: 'text-align:center;padding:2rem' }, icon('plan'), h('div', { style: 'margin:.4rem 0;font-weight:600' }, 'No floor plan image yet.'),
    h('div', { style: 'max-width:26rem;margin:0 auto' }, 'To add one, switch to ', h('b', 'Edit plan'), ', open ', h('b', 'Plan settings'), ' at the top right and choose ', h('b', 'Upload plan image…'), '.'),
    h('div.hint', { style: 'margin-top:.4rem' }, 'PNG, JPG, SVG, WebP or GIF, up to 12 MB.'));
  const layer = h('div.plan-layer');
  const canvas = h('div.plan-canvas.empty', emptyMsg, img, layer);
  img.hidden = true;
  const viewport = h('div.plan-viewport', { tabindex: -1 }, canvas);
  img.onload = () => { hasImg = true; img.hidden = false; emptyMsg.hidden = true; canvas.classList.remove('empty'); if (!layout.fitted && !layout.zoomSet) fit(); refreshTools(); };
  img.onerror = () => { hasImg = false; img.hidden = true; emptyMsg.hidden = false; canvas.classList.add('empty'); refreshTools(); };
  const loadImg = () => { img.src = '/api/plan?floor=' + cur + '&t=' + Date.now(); };
  const hasPlanImage = () => (S.state?.plans ? S.state.plans.includes(cur) : cur === 0 && !!S.state?.plan);

  // ----- floors -----
  function renderFloors() {
    const fl = floors();
    floorTabs.hidden = fl.length < 2;
    syncKeyed(floorTabs, fl, f => String(f.n),
      f => h('button', { type: 'button', onclick: () => showFloor(f.n) }),
      (b, f) => { setText(b, f.name); setAttr(b, 'aria-pressed', String(f.n === cur)); setAttr(b, 'title', 'Show ' + f.name); });
  }
  function showFloor(n) {
    if (n === cur || !floorObj(n)) return;
    const old = floorObj(cur); if (old && layout.floors) old.zoom = layout.zoom;
    cur = n; sel = null;
    const z = floorObj(n).zoom;
    delete layout.fitted; delete layout.zoomSet;
    if (z) { layout.zoom = z; layout.zoomSet = true; } else layout.zoom = 100;
    applyZoom(); viewport.scrollLeft = viewport.scrollTop = 0;
    planKnown = hasPlanImage(); if (planKnown) loadImg(); else img.onerror();
    save(); update();
  }
  /** Asks for a floor name. @returns the name, or null if cancelled */
  function askName(title, value, confirmLabel) {
    return new Promise(resolve => {
      let result = null;
      const input = h('input.input', { type: 'text', maxLength: 24, value, 'aria-label': 'Name of the floor', style: 'width:100%' });
      const ok = close => { const v = input.value.trim(); if (!v) { input.focus(); return; } result = v; close(); };
      const dlg = modal({ title, body: h('div', h('label.hint', { style: 'display:block;margin-bottom:.3rem' }, 'Name of the floor (for example Ground floor, 1st floor, Basement)'), input),
        buttons: close => [h('button.btn', { onclick: close }, 'Cancel'), h('button.btn.primary', { onclick: () => ok(close) }, confirmLabel)] });
      input.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); ok(() => dlg.close()); } });
      dlg.addEventListener('close', () => resolve(result));
      input.focus(); input.select();
    });
  }
  async function addFloor() {
    const fl = floors();
    if (fl.length >= MAX_FLOORS) return;
    const name = await askName('Add a floor', 'Floor ' + (fl.length + 1), 'Add floor');
    if (!name) return;
    let n = 0; while (fl.some(f => f.n === n)) n++;
    layout.floors = [...fl.map(f => ({ ...f })), { n, name }];
    // a floor number that was used before may still have an image: a new floor starts empty
    if (S.state?.plans?.includes(n)) { try { await api.planRemove(n); } catch (e) { /* the old image stays, it can be replaced */ } }
    showFloor(n); renderFloors();
    toast(`"${name}" was added. Upload its plan image under Plan settings.`, 'ok');
  }
  async function renameFloor() {
    const f = floorObj(cur); if (!f) return;
    const name = await askName('Rename floor', f.name, 'Rename');
    if (!name || name === f.name) return;
    layout.floors = floors().map(x => (x.n === cur ? { ...x, name } : { ...x }));
    save(); update();
  }
  async function removeFloor() {
    const fl = floors(); if (fl.length < 2) return;
    const f = floorObj(cur), count = layout.items.filter(onFloor).length;
    const ok = await confirmDialog({ title: 'Remove floor', danger: true, confirmLabel: 'Remove floor',
      message: `Remove "${f.name}" with its plan image?` + (count ? ` The ${count} symbol${count === 1 ? '' : 's'} on it ${count === 1 ? 'is' : 'are'} taken off the plan (the devices themselves stay).` : '') });
    if (!ok) return;
    try { await api.planRemove(cur); } catch (e) { toast(`The plan image could not be removed: ${e.message}`, 'error'); return; }
    layout.items = layout.items.filter(i => !onFloor(i));
    layout.floors = fl.filter(x => x.n !== cur).map(x => ({ ...x }));
    const next = layout.floors[0].n; cur = -1; showFloor(next);
    toast(`"${f.name}" was removed.`, 'ok');
  }

  // ----- palette (edit mode) -----
  const palSearch = h('input.input', { type: 'search', placeholder: 'Search channels', 'aria-label': 'Search channels', style: 'margin:.5rem .85rem;width:calc(100% - 1.7rem)' });
  palSearch.addEventListener('input', renderPalette);
  const palList = h('div');
  const palette = h('aside.plan-side', { 'aria-label': 'Channels that can be placed' }, h('h3', 'Place on the plan'), h('div.hint', { style: 'padding:0 .85rem' }, 'Click a channel to place it in the middle of the view, or drag it onto the plan. Select a symbol to change its label and icon; drag it (or use the arrow keys) to move it.'), palSearch, palList);

  // ----- properties (edit mode) -----
  const props = h('aside.plan-side.right', { 'aria-label': 'Symbol properties' });

  const wrap = h('div.plan-wrap', palette, viewport, props);
  root.append(toolbar, wrap);

  // ----- layout helpers -----
  // The symbols grow and shrink with the plan: scale = symbol size setting x width of the plan / REF_W
  const REF_W = 1100;
  function applySize() {
    const w = canvas.clientWidth || REF_W;
    setStyle(canvas, '--mk-scale', ((layout.size || 1) * clamp(w / REF_W, .15, 4)).toFixed(3));
    sRange.value = Math.round((layout.size || 1) * 100);
  }
  function applyZoom() { canvas.style.width = layout.zoom + '%'; zRange.value = layout.zoom; setText(zVal, layout.zoom + ' %'); applySize(); }
  function setZoom(z, anchor) {
    z = clamp(Math.round(z), MIN_Z, MAX_Z);
    let fx = .5, fy = .5, cx, cy;
    const r = canvas.getBoundingClientRect(), vr = viewport.getBoundingClientRect();
    if (anchor) { cx = anchor.x; cy = anchor.y; } else { cx = vr.left + vr.width / 2; cy = vr.top + vr.height / 2; }
    fx = (cx - r.left) / r.width; fy = (cy - r.top) / r.height;
    layout.zoom = z; layout.zoomSet = true; applyZoom();
    const r2 = canvas.getBoundingClientRect();
    viewport.scrollLeft += (r2.left + fx * r2.width) - cx;
    viewport.scrollTop += (r2.top + fy * r2.height) - cy;
    save();
  }
  function fit() {
    if (!img.naturalWidth) return;
    const vw = viewport.clientWidth - 2, vh = viewport.clientHeight - 2;
    const byH = (vh / (img.naturalHeight / img.naturalWidth)) / vw * 100;
    layout.zoom = clamp(Math.floor(Math.min(100, byH)), MIN_Z, MAX_Z); layout.fitted = true; delete layout.zoomSet; applyZoom();
    viewport.scrollLeft = viewport.scrollTop = 0; save();
  }
  function setEdit(v) { S.planEdit = v; if (!v) { sel = null; selSet.clear(); } else closePop(); update(); }
  function refreshTools() { symBox.hidden = !S.planEdit; settingsBox.hidden = !S.planEdit; bUndo.hidden = !S.planEdit; bUndo.disabled = !undoStack.length; }

  // ----- operating card next to a symbol (operate mode) -----
  let pop = null;
  function closePop() {
    if (!pop) return;
    document.removeEventListener('pointerdown', pop.outside, true); document.removeEventListener('keydown', pop.key, true);
    pop.el.remove(); pop = null;
  }
  function placePop() {
    if (!pop || !pop.anchor.isConnected) return;
    const a = pop.anchor.getBoundingClientRect(), w = pop.el.offsetWidth, hh = pop.el.offsetHeight, gap = 10;
    let left = a.right + gap; if (left + w > innerWidth - 8) left = a.left - w - gap; if (left < 8) left = clamp(a.left, 8, Math.max(8, innerWidth - w - 8));
    pop.el.style.left = left + 'px'; pop.el.style.top = clamp(a.top - 16, 8, Math.max(8, innerHeight - hh - 8)) + 'px';
  }
  /** Shows the operating card of a device beside its symbol, so it can be operated without leaving the plan. */
  function openPop(devName, anchor) {
    if (pop && pop.dev === devName) { closePop(); return; }
    closePop();
    const d = device(devName); if (!d) return;
    const card = createCard(d, { menu: () => h('span.pop-btns',
      h('button.btn.quiet.sm', { type: 'button', title: 'Open the device details', onclick: () => { closePop(); selectDevice(devName, { drawer: true }); } }, 'Details'),
      h('button.btn.quiet.icon', { type: 'button', 'aria-label': 'Close', title: 'Close', onclick: closePop }, icon('close'))) });
    const el = h('div.mk-pop', { role: 'dialog', 'aria-label': 'Operate ' + devName }, card.el);
    document.body.append(el);
    pop = { dev: devName, el, card, anchor,
      outside: e => { if (!el.contains(e.target) && !e.target.closest('dialog, .toast')) { const again = anchor.contains(e.target); closePop(); if (again) e.stopPropagation(); } },
      key: e => { if (e.key === 'Escape' && !document.querySelector('dialog[open]')) { e.stopPropagation(); closePop(); } } };
    card.update(d); placePop();
    document.addEventListener('pointerdown', pop.outside, true); document.addEventListener('keydown', pop.key, true);
  }
  viewport.addEventListener('scroll', closePop);
  document.addEventListener('keydown', e => {
    if (!S.planEdit || root.hidden || root.offsetParent === null || !(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== 'z') return;
    const a = document.activeElement; if (a && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName)) return;
    e.preventDefault(); undo();
  });

  async function upload() {
    const f = file.files[0]; if (!f) return;
    const ext = (f.name.split('.').pop() || '').toLowerCase();
    try { await api.planUpload(ext, await f.arrayBuffer(), cur); toast('The floor plan image was uploaded.', 'ok'); delete layout.fitted; planKnown = true; loadImg(); }
    catch (e) { toast(`The plan image could not be uploaded: ${e.message}`, 'error'); }
    file.value = '';
  }
  async function removePlanImage() {
    const ok = await confirmDialog({ title: 'Remove plan image', danger: true, confirmLabel: 'Remove image', message: 'Remove the plan image of this floor? The symbol placements are kept, so a new image can be used with the same layout.' });
    if (!ok) return;
    try { await api.planRemove(cur); planKnown = false; img.onerror(); toast('The plan image was removed.', 'ok'); } catch (e) { toast(`The plan image could not be removed: ${e.message}`, 'error'); }
  }

  // ----- markers -----
  const pointsMap = () => { const m = new Map(); for (const d of S.state?.devices || []) for (const p of pointsOf(d)) m.set(p.id, p); return m; };
  const itemOf = id => layout.items.find(i => i.id === id);

  function createMarker(p) {
    const el = h('div.mk', { dataset: { id: p.id } });
    const body = h('div.mk-body'); const label = h('div.mk-label'); const infoBtn = h('button.mk-info', { type: 'button', 'aria-label': 'Show device details', title: 'Show device details', onclick: ev => { ev.stopPropagation(); selectDevice(p.dev, { drawer: true }); } }, icon('info'));
    let upd, press = null; const card = h('div.mk-card');
    switch (p.type) {
      case 'lamp': {
        const ib = h('span.mk-ico', icon('lampOff')); const t = h('span.t');
        body.append(ib, t);
        // a colour channel shows its colour on the icon (col: '#RRGGBB', or 'k<Kelvin>' for tunable white)
        upd = q => { setCls(el, 'on', q.on); paintIcon(ib, p.id, pointDefault(p), q.on); setStyle(ib, 'color', q.on && q.col ? (q.col[0] === 'k' ? kelvinCss(+q.col.slice(1)) : q.col) : ''); setText(t, q.pct != null && q.on ? q.pct + ' %' : ''); return q.on + ':' + q.pct + ':' + (q.col || '') + ':' + (S.icons[p.id]?.n || ''); };
        card.classList.add('opens'); card.addEventListener('click', () => { if (!S.planEdit) openPop(p.dev, el); }); card.title = 'Open the operating card of ' + p.dev;
        break;
      }
      case 'button': {
        const ib = h('span.mk-ico', icon('button'));
        const b = h('button.mk-btn', { type: 'button', 'aria-label': 'Press ' + p.dev, title: 'Press' }, ib);
        press = b; card.title = 'Press'; card.addEventListener('click', () => { if (!S.planEdit) operate('Button press', () => api.act(p.dev, 'press', 0)); });
        body.append(b); upd = () => { paintIcon(ib, p.id, pointDefault(p)); return S.icons[p.id]?.n || ''; }; break;
      }
      case 'key': {
        const ib = h('span.mk-ico', icon(p.sym));
        const b = h('button.mk-btn', { type: 'button', 'aria-label': p.name + ' of ' + p.dev }, ib);
        press = b; card.title = p.name + ' (hold for a long press)';
        // the whole card is the key: icon, value and name
        let held = false;
        const up = () => { if (!held) return; held = false; card.classList.remove('down'); operate('Key', () => api.act(p.dev, 'up', p.keyNo)); };
        card.addEventListener('pointerdown', e => { if (S.planEdit || held || e.button !== 0) return; held = true; card.setPointerCapture(e.pointerId); card.classList.add('down'); operate('Key', () => api.act(p.dev, 'down', p.keyNo)); });
        card.addEventListener('pointerup', up); card.addEventListener('pointercancel', up); card.addEventListener('lostpointercapture', up);
        body.append(b); upd = () => { paintIcon(ib, p.id, pointDefault(p)); return S.icons[p.id]?.n || ''; }; break;
      }
      case 'slider': {
        const r = h('input', { type: 'range', min: 0, max: 100, value: 0, 'aria-label': 'Slider of ' + p.dev }); const t = h('span.t.mk-val');
        let active = false, until = 0;
        r.addEventListener('pointerdown', () => { active = true; }); const end = () => { active = false; until = Date.now() + 500; };
        r.addEventListener('pointerup', end); r.addEventListener('pointercancel', end);
        r.addEventListener('input', () => { setText(t, r.value + ' %'); api.act(p.dev, 'sliderLive', r.value).catch(() => {}); });
        r.addEventListener('change', () => operate('Slider', () => api.act(p.dev, 'slider', r.value)));
        // one symbol: on/off button on the left, the slider to the right of it, name and value below
        const ib = h('span.mk-ico', icon('button'));
        const b = h('button.mk-btn', { type: 'button', 'aria-label': 'Press ' + p.dev, title: 'Switch on / off', onclick: () => { if (!S.planEdit) operate('Button press', () => api.act(p.dev, 'press', 0)); } }, ib);
        b.classList.add('own'); // only the icon block presses; the slider stays a slider
        const nm = h('span'); label.append(nm, t); label._name = nm; el.classList.add('mk-slider');
        body.append(b, r);
        upd = q => { setCls(el, 'on', !!q.on); paintIcon(ib, p.id, 'button'); if (!active && Date.now() > until && document.activeElement !== r) { if (+r.value !== q.val) r.value = q.val; setText(t, q.val + ' %'); } return q.on + ':' + (S.icons[p.id]?.n || ''); };
        break;
      }
      case 'blind': {
        const w = h('i'); const t = h('span.t'); const win = h('div.mk-win', w); const ib = h('span.mk-ico', icon('blind'));
        body.append(win, ib, t);
        upd = q => { const own = hasCustomIcon(p.id); win.hidden = own; ib.hidden = !own; if (own) paintIcon(ib, p.id, pointDefault(p)); setStyle(w, 'height', q.pos + '%'); setText(t, q.pos + ' %'); body.title = 'Blind position: ' + q.pos + ' % closed'; return q.pos; };
        card.classList.add('opens'); card.addEventListener('click', () => { if (!S.planEdit) openPop(p.dev, el); }); card.title = 'Open the operating card of ' + p.dev;
        break;
      }
      case 'display': {
        const t = h('span.t');
        const ib = h('span.mk-ico', icon('display'));
        body.append(ib, t);
        upd = q => { paintIcon(ib, p.id, pointDefault(p)); setText(t, q.text); return q.text; };
        card.classList.add('opens'); card.addEventListener('click', () => { if (!S.planEdit) openPop(p.dev, el); }); card.title = 'Open the operating card of ' + p.dev;
        break;
      }
      case 'logic': {
        const t = h('span.t');
        const ib = h('span.mk-ico', icon('logic'));
        body.append(ib, t);
        upd = q => { paintIcon(ib, p.id, pointDefault(p)); setText(t, q.text); return q.text; };
        card.classList.add('opens'); card.addEventListener('click', () => { if (!S.planEdit) openPop(p.dev, el); }); card.title = 'Open the operating card of ' + p.dev;
        break;
      }
      case 'contact': {
        const ib = h('span.mk-ico', icon('contact'));
        const b = h('button.mk-btn', { type: 'button', 'aria-label': p.name + ' of ' + p.dev, title: p.name + ': click to close or open the contact' }, ib);
        press = b; card.title = p.name + ': click to close or open the contact';
        card.addEventListener('click', () => { if (!S.planEdit) operate('Contact', () => api.act(p.dev, 'toggle', p.keyNo)); });
        const t = h('span.t'); body.append(b, t);
        upd = q => { setCls(el, 'on', q.closed); paintIcon(ib, p.id, pointDefault(p)); setText(t, q.closed ? 'Closed' : 'Open'); return q.closed + ':' + (S.icons[p.id]?.n || ''); };
        break;
      }
      case 'info': {
        const t = h('span.t'); const ib = h('span.mk-ico', icon(p.sym));
        body.append(ib, t);
        upd = q => { setCls(el, 'on', !!q.on); paintIcon(ib, p.id, pointDefault(p)); setText(t, q.text); return q.on + ':' + q.text + ':' + (S.icons[p.id]?.n || ''); };
        card.classList.add('opens'); card.addEventListener('click', () => { if (!S.planEdit) openPop(p.dev, el); }); card.title = 'Open the operating card of ' + p.dev;
        break;
      }
      case 'sensor': {
        const t = h('span.t');
        const ib = h('span.mk-ico', icon(SENSORS[p.sensor].icon));
        body.append(ib, t);
        upd = q => { paintIcon(ib, p.id, pointDefault(p)); setText(t, q.text); return q.text; };
        card.classList.add('opens'); card.addEventListener('click', () => { if (!S.planEdit) openPop(p.dev, el); }); card.title = 'Open the operating card of ' + p.dev;
        break;
      }
    }
    card.append(body, label); if (press) card.classList.add('act');
    el.append(card, infoBtn);
    el._upd = upd; el._label = label;
    el.addEventListener('pointerdown', e => {
      if (!S.planEdit || e.button !== 0) return;
      e.preventDefault(); e.stopPropagation();
      const add = e.shiftKey || e.ctrlKey || e.metaKey;
      if (add) { selectMarker(p.id, true); return; } // Shift or Ctrl + click: add to / take out of the selection
      if (!selSet.has(p.id)) selectMarker(p.id);
      else if (sel !== p.id) { sel = p.id; update(); }
      const it = itemOf(p.id); if (!it) return;
      // all selected symbols move together
      const group = layout.items.filter(i => selSet.has(i.id) && onFloor(i)).map(i => ({ it: i, x: i.x, y: i.y, el: layer.querySelector(`[data-id="${CSS.escape(i.id)}"]`) }));
      dragging = { id: p.id, it, el, group, px: e.clientX, py: e.clientY, moved: false }; el.setPointerCapture(e.pointerId);
    });
    el.addEventListener('pointermove', e => {
      if (!dragging || dragging.id !== p.id) return;
      const r = canvas.getBoundingClientRect();
      let dx = (e.clientX - dragging.px) / r.width, dy = (e.clientY - dragging.py) / r.height;
      if (!dragging.moved) { if (Math.abs(e.clientX - dragging.px) + Math.abs(e.clientY - dragging.py) < 3) return; dragging.moved = true; pushUndo(); }
      // keep the whole group on the plan
      for (const g of dragging.group) { dx = clamp(dx, .01 - g.x, .99 - g.x); dy = clamp(dy, .01 - g.y, .99 - g.y); }
      for (const g of dragging.group) { g.it.x = g.x + dx; g.it.y = g.y + dy; if (g.el) { g.el.style.left = g.it.x * 100 + '%'; g.el.style.top = g.it.y * 100 + '%'; } }
    });
    const endDrag = () => { if (dragging && dragging.id === p.id) { const moved = dragging.moved; dragging = null; if (moved) save(); renderProps(); } };
    el.addEventListener('pointerup', endDrag); el.addEventListener('pointercancel', endDrag);
    el.addEventListener('keydown', e => {
      if (!S.planEdit || !e.key.startsWith('Arrow')) return;
      if (!itemOf(p.id)) return; const st = e.shiftKey ? .02 : .005;
      if (!selSet.has(p.id)) selectMarker(p.id);
      if (Date.now() - lastNudge > 1500) pushUndo(); lastNudge = Date.now(); // a run of key presses is one step to undo
      for (const it of layout.items.filter(i => selSet.has(i.id) && onFloor(i))) {
        if (e.key === 'ArrowLeft') it.x -= st; if (e.key === 'ArrowRight') it.x += st; if (e.key === 'ArrowUp') it.y -= st; if (e.key === 'ArrowDown') it.y += st;
        it.x = clamp(it.x, .01, .99); it.y = clamp(it.y, .01, .99);
        const m = layer.querySelector(`[data-id="${CSS.escape(it.id)}"]`); if (m) { m.style.left = it.x * 100 + '%'; m.style.top = it.y * 100 + '%'; }
      }
      save(); e.preventDefault();
    });
    return el;
  }

  function updateMarker(el, p, it) {
    if (!dragging) { setStyle(el, 'left', it.x * 100 + '%'); setStyle(el, 'top', it.y * 100 + '%'); }
    const sig = el._upd(p);
    if (el._sig !== undefined && el._sig !== sig && !S.planEdit) { el.classList.remove('flash'); void el.offsetWidth; el.classList.add('flash'); }
    el._sig = sig;
    setText(el._label._name || el._label, it.label || p.label);
    setCls(el, 'edit', S.planEdit); setCls(el, 'sel', S.planEdit && selSet.has(p.id));
    setAttr(el, 'tabindex', S.planEdit ? 0 : null);
    const d = device(p.dev);
    setCls(el, 'hl', !!S.gaSel && !!d && objectsOfPoint(d, p).some(n => (d.assoc || []).some(a => a.obj === n && a.ga === S.gaSel)));
    // in edit mode the controls must not operate
    el.querySelector('.mk-body').style.pointerEvents = '';
    for (const c of el.querySelectorAll('.mk-body input, .mk-body button')) c.style.pointerEvents = S.planEdit ? 'none' : '';
    el.setAttribute('role', 'group'); el.setAttribute('aria-label', (it.label || p.label) + ', ' + p.name);
  }

  function selectMarker(id, additive) {
    if (additive) { if (selSet.has(id)) { selSet.delete(id); sel = [...selSet].at(-1) || null; } else { selSet.add(id); sel = id; } }
    else { selSet.clear(); selSet.add(id); sel = id; }
    props.dataset.sel = ''; update();
  }

  // ----- palette -----
  function placeAt(id, x, y, cascade) {
    if (itemOf(id)) return;
    if (cascade) { // several symbols placed from the list must not end up exactly on top of each other
      let n = 0;
      while (layout.items.some(i => onFloor(i) && Math.abs(i.x - (x + n * .075)) < .03 && Math.abs(i.y - (y + n * .07)) < .03) && n < 10) n++;
      x += n * .075; y += n * .07;
    }
    pushUndo();
    layout.items.push({ id, x: clamp(x, .02, .98), y: clamp(y, .02, .98), ...(cur ? { f: cur } : {}) });
    sel = id; selSet.clear(); selSet.add(id); save(); update();
  }
  function viewCenter() {
    const r = canvas.getBoundingClientRect(), v = viewport.getBoundingClientRect();
    const cx = clamp((v.left + v.width / 2 - r.left) / r.width, .05, .95), cy = clamp((v.top + v.height / 2 - r.top) / r.height, .05, .95);
    return [cx, cy];
  }
  function renderPalette() {
    const placed = new Set(layout.items.map(i => i.id)), q = palSearch.value.trim().toLowerCase();
    const blocks = [];
    for (const d of S.state?.devices || []) {
      const free = pointsOf(d).filter(p => !placed.has(p.id) && (!q || (d.name + ' ' + p.name).toLowerCase().includes(q)));
      if (!free.length) continue;
      blocks.push(h('div.pal-group', d.name), ...free.map(p => {
        const pib = h('span.mk-ico', icon('info')); paintIcon(pib, p.id, pointDefault(p));
        const b = h('button.pal-item', { type: 'button', draggable: true, title: 'Click to place, or drag onto the plan', onclick: () => placeAt(p.id, ...viewCenter(), true) }, pib, p.name);
        b.addEventListener('dragstart', e => e.dataTransfer.setData('text/plain', p.id));
        return b;
      }));
    }
    const sig = blocks.map(b => b.textContent).join('|') + Object.values(S.icons).map(e => e.n).join();
    if (palList.dataset.sig === sig) return;
    palList.dataset.sig = sig;
    palList.replaceChildren(...(blocks.length ? blocks : [h('div.empty', 'All channels are placed on the plan.')]));
  }
  canvas.addEventListener('dragover', e => { if (S.planEdit) e.preventDefault(); });
  canvas.addEventListener('drop', e => {
    if (!S.planEdit) return; e.preventDefault();
    const r = canvas.getBoundingClientRect();
    placeAt(e.dataTransfer.getData('text/plain'), (e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height);
  });

  // ----- properties -----
  /** Puts the selected symbols in one row (same height) or one column, at the average position. */
  function align(axis) {
    const its = layout.items.filter(i => selSet.has(i.id) && onFloor(i)); if (its.length < 2) return;
    pushUndo(); const avg = its.reduce((a, i) => a + i[axis], 0) / its.length;
    for (const i of its) i[axis] = avg;
    save(); update();
  }
  /** Spreads the selected symbols evenly between the two outermost ones. */
  function spread(axis) {
    const its = layout.items.filter(i => selSet.has(i.id) && onFloor(i)).sort((a, b) => a[axis] - b[axis]); if (its.length < 3) return;
    pushUndo(); const a = its[0][axis], b = its.at(-1)[axis];
    its.forEach((i, k) => { i[axis] = a + (b - a) * k / (its.length - 1); });
    save(); update();
  }
  function renderProps() {
    if (selSet.size > 1) {
      const n = selSet.size, key = 'multi:' + [...selSet].join(',');
      if (props.dataset.sel === key) return; props.dataset.sel = key;
      props.replaceChildren(
        h('div.props-head', h('div.grow', h('h3', `${n} symbols selected`), h('div.mute', 'Drag one to move them all, or use the arrow keys.')), h('button.btn.quiet.icon', { 'aria-label': 'Close', title: 'Clear the selection', onclick: () => { sel = null; selSet.clear(); update(); } }, icon('close'))),
        h('div', { style: 'padding:.4rem .85rem .85rem;display:grid;gap:.5rem' },
          h('button.btn', { onclick: () => align('y') }, 'Align in a row'),
          h('button.btn', { onclick: () => align('x') }, 'Align in a column'),
          n > 2 ? h('button.btn', { onclick: () => spread('x') }, 'Spread evenly, left to right') : null,
          n > 2 ? h('button.btn', { onclick: () => spread('y') }, 'Spread evenly, top to bottom') : null,
          h('button.btn', { onclick: removeFromPlan }, icon('close'), 'Remove from plan'),
          h('div.hint', 'Shift + click adds a symbol to the selection or takes it out. Undo (Ctrl+Z) takes back the last change.')));
      return;
    }
    const it = sel && itemOf(sel), p = it && pointsMap().get(sel);
    if (!it || !p) {
      props.replaceChildren(); props.dataset.sel = ''; return;
    }
    const d = device(p.dev);
    const key = sel + '|' + (it.label || '');
    if (props.dataset.sel === sel && props.contains(document.activeElement)) { return; } // typing a label: do not rebuild
    props.dataset.sel = sel;
    const label = h('input.input', { type: 'text', value: it.label || '', placeholder: p.label, maxlength: 40, id: 'mk-label', 'aria-describedby': 'mk-label-help' });
    label.addEventListener('input', () => { it.label = label.value.trim() || undefined; save(); const el = layer.querySelector(`[data-id="${CSS.escape(sel)}"]`); if (el) setText(el._label._name || el._label, it.label || p.label); });
    const objs = new Set(objectsOfPoint(d, p));
    const gas = (d.assoc || []).filter(a => objs.has(a.obj));
    const catObjs = S.cat[d.product]?.objects || [];
    props.replaceChildren(
      h('div.props-head', h('div.grow', h('h3', `${p.dev} · ${p.name}`), it.label ? h('div.mute', 'Shown on the plan as “' + it.label + '”') : null), h('button.btn.quiet.icon', { 'aria-label': 'Close properties', title: 'Close', onclick: () => { sel = null; selSet.clear(); update(); } }, icon('close'))),
      h('div', { style: 'padding:.4rem .85rem .85rem;display:grid;gap:.85rem' },
        h('div.field', { style: 'margin:0' }, h('label', { for: 'mk-label' }, 'Label on the plan'), label, h('div.hint#mk-label-help', 'Leave empty to use the default name.')),
        h('div.field', { style: 'margin:0' }, h('label', 'Icon'), (() => {
          const ib = h('span.ico-box', icon('info')); paintIcon(ib, sel, pointDefault(p));
          const id = sel;
          return h('div', { style: 'display:flex;gap:.4rem;align-items:center;flex-wrap:wrap' }, ib,
            h('button.btn.sm', { onclick: async () => {
              const r = await pickIcon({ title: `Icon for ${p.label}`, kind: pointKind(p), currentName: S.icons[id]?.n, withOff: p.type === 'lamp' });
              if (r === undefined) return; await setIcon(id, r); props.dataset.sel = ''; renderProps();
            } }, 'Change…'),
            hasCustomIcon(id) ? h('button.btn.sm.quiet', { onclick: async () => { await setIcon(id, null); props.dataset.sel = ''; renderProps(); } }, 'Reset') : null);
        })()),
        h('dl.kv', h('dt', 'Device'), h('dd', h('button.btn.sm', { onclick: () => selectDevice(p.dev, { drawer: true }) }, p.dev)), h('dt', 'Channel'), h('dd', p.name),
          h('dt', 'Address'), h('dd.mono', d.unassigned ? 'not assigned' : d.address)),
        h('div', h('div.hint', 'Configured group addresses'), gas.length ? gas.map(a => h('div', h('button.ga-chip', { onclick: () => showGroupAddress(a.ga) }, a.ga), h('span.hint', ' ' + (catObjs.find(o => o.number === a.obj)?.text || a.text)))) : h('div.mute', d.loaded ? 'none for this channel' : 'none (not downloaded yet)')),
        h('div', h('button.btn', { onclick: removeFromPlan }, icon('close'), 'Remove from plan'),
          h('div.hint', { style: 'margin-top:.4rem' }, 'Only removes this symbol from the floor plan. The device stays in the installation.'))));
  }
  function removeFromPlan() {
    pushUndo();
    layout.items = layout.items.filter(i => !selSet.has(i.id) && i.id !== sel); sel = null; selSet.clear(); save(); update();
  }

  // ----- interactions on the viewport -----
  viewport.addEventListener('pointerdown', e => {
    if (e.button !== 0 || e.target.closest('.mk')) return;
    if (S.planEdit && (sel || selSet.size) && !e.shiftKey) { sel = null; selSet.clear(); update(); }
    panning = { x: e.clientX, y: e.clientY, sl: viewport.scrollLeft, st: viewport.scrollTop };
    viewport.setPointerCapture(e.pointerId); viewport.classList.add('panning');
  });
  viewport.addEventListener('pointermove', e => { if (!panning) return; viewport.scrollLeft = panning.sl - (e.clientX - panning.x); viewport.scrollTop = panning.st - (e.clientY - panning.y); });
  const endPan = () => { panning = null; viewport.classList.remove('panning'); };
  viewport.addEventListener('pointerup', endPan); viewport.addEventListener('pointercancel', endPan);
  viewport.addEventListener('wheel', e => { if (!e.ctrlKey) return; e.preventDefault(); setZoom(layout.zoom * (e.deltaY < 0 ? 1.1 : 1 / 1.1), { x: e.clientX, y: e.clientY }); }, { passive: false });
  new ResizeObserver(() => { if (!layout.zoomSet && hasImg && !root.hidden) fit(); applySize(); }).observe(viewport);
  new ResizeObserver(() => applySize()).observe(canvas);

  async function ensureLoaded() {
    if (loaded) return; loaded = true;
    try { const l = await api.layout(); layout = { items: [], zoom: 100, size: 1, ...l }; if (!layout.items) layout.items = []; } catch (e) { toast(`The floor plan layout could not be loaded: ${e.message}`, 'error'); loaded = false; return; }
    cur = floorObj(layout.floor) ? layout.floor : floors()[0].n;
    if (floorObj(cur).zoom) layout.zoom = floorObj(cur).zoom;
    if (layout.zoom) layout.zoomSet = layout.zoom !== 100 || undefined;
    applyZoom(); applySize(); planKnown = hasPlanImage(); if (planKnown) loadImg(); else img.onerror();
  }

  function update() {
    if (root.parentElement && root.hidden) return;
    ensureLoaded();
    const hp = hasPlanImage();
    renderFloors();
    if (loaded && hp !== planKnown) { planKnown = hp; if (hp) loadImg(); else img.onerror(); }
    bOp.setAttribute('aria-pressed', String(!S.planEdit)); bEd.setAttribute('aria-pressed', String(S.planEdit));
    palette.hidden = !S.planEdit; props.hidden = !(S.planEdit && (sel || selSet.size > 1));
    if (pop) { const pd = device(pop.dev); if (!pd || S.planEdit || !pop.anchor.isConnected) closePop(); else { pop.card.update(pd); placePop(); } }
    refreshTools();
    const pts = pointsMap();
    const items = layout.items.filter(i => pts.has(i.id) && onFloor(i));
    syncKeyed(layer, items, i => i.id, i => createMarker(pts.get(i.id)), (el, i) => updateMarker(el, pts.get(i.id), i));
    if (S.planEdit) { renderPalette(); renderProps(); }
  }
  on('state', () => { if (!root.hidden) update(); });
  on('icons', () => { if (!root.hidden) update(); });
  // A device was renamed: the server changed the ids in the layout file, so load it again (saving the old copy would undo it).
  on('renamed', () => { loaded = false; if (!root.hidden) update(); });
  on('devices', () => { if (!root.hidden) update(); });
  on('ga', () => { if (!root.hidden) update(); });
  return { el: root, update: () => { root.hidden = false; update(); } };
}
