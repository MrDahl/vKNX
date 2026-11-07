import { h, icon, setText, setCls, setStyle, setAttr, syncKeyed, clamp, debounce, naturalCmp } from '../util.js';
import { api } from '../api.js';
import { S, on, emit, device, selectDevice, showGroupAddress } from '../bus.js';
import { pointsOf, objectsOfPoint, SENSORS } from '../devinfo.js';
import { operate } from '../cards.js';
import { confirmDialog, toast, menuButton } from '../dialogs.js';
import { paintIcon, setIcon, hasCustomIcon, pointDefault, pointKind } from '../iconStore.js';
import { pickIcon } from '../iconPicker.js';

const MIN_Z = 20, MAX_Z = 300;

/**
 * Floor plan: "Operate" mode (maximum space, inputs work, no editing tools) and "Edit" mode (channel list, move symbols,
 * properties). Placements are stored in plan-layout.json as { items: [{id:"<device>|<channel>", x, y, label?}], zoom, size }.
 */
export function createPlan() {
  const root = h('div.view', { hidden: true });
  let layout = { items: [], zoom: 100, size: 1 };
  let loaded = false, hasImg = false, planKnown = null, sel = null, dragging = null, panning = null;

  const save = debounce(async () => {
    try { await api.saveLayout({ items: layout.items, zoom: layout.zoom, size: layout.size }); } catch (e) { toast(`The floor plan layout could not be saved: ${e.message}`, 'error'); }
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
    ...(hasImg ? [{ label: 'Remove plan image…', icon: 'trash', danger: true, onclick: removePlanImage }] : [])] });
  const settingsBox = h('span.plan-settings', planMenu);
  const toolbar = h('div.toolbar', seg,
    h('button.btn', { onclick: fit, title: 'Fit the whole plan into the window' }, icon('fit'), h('span.btn-text.opt', 'Fit to window')),
    h('span.zoom-box', h('button.btn.icon', { 'aria-label': 'Zoom out', title: 'Zoom out', onclick: () => setZoom(layout.zoom - 15) }, icon('zoomOut')), zRange, h('button.btn.icon', { 'aria-label': 'Zoom in', title: 'Zoom in', onclick: () => setZoom(layout.zoom + 15) }, icon('zoomIn')), zVal),
    symBox, h('span.grow'), settingsBox, file);

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
  const loadImg = () => { img.src = '/api/plan?' + Date.now(); };

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
  function applySize() { setStyle(canvas, '--mk-scale', layout.size || 1); sRange.value = Math.round((layout.size || 1) * 100); }
  function applyZoom() { canvas.style.width = layout.zoom + '%'; zRange.value = layout.zoom; setText(zVal, layout.zoom + ' %'); }
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
  function setEdit(v) { S.planEdit = v; if (!v) sel = null; update(); }
  function refreshTools() { symBox.hidden = !S.planEdit; settingsBox.hidden = !S.planEdit; }

  async function upload() {
    const f = file.files[0]; if (!f) return;
    const ext = (f.name.split('.').pop() || '').toLowerCase();
    try { await api.planUpload(ext, await f.arrayBuffer()); toast('The floor plan image was uploaded.', 'ok'); delete layout.fitted; planKnown = true; loadImg(); }
    catch (e) { toast(`The plan image could not be uploaded: ${e.message}`, 'error'); }
    file.value = '';
  }
  async function removePlanImage() {
    const ok = await confirmDialog({ title: 'Remove plan image', danger: true, confirmLabel: 'Remove image', message: 'Remove the floor plan image? The symbol placements are kept, so a new image can be used with the same layout.' });
    if (!ok) return;
    try { await api.planRemove(); planKnown = false; img.onerror(); toast('The plan image was removed.', 'ok'); } catch (e) { toast(`The plan image could not be removed: ${e.message}`, 'error'); }
  }

  // ----- markers -----
  const pointsMap = () => { const m = new Map(); for (const d of S.state?.devices || []) for (const p of pointsOf(d)) m.set(p.id, p); return m; };
  const itemOf = id => layout.items.find(i => i.id === id);

  function createMarker(p) {
    const el = h('div.mk', { dataset: { id: p.id } });
    const body = h('div.mk-body'); const label = h('div.mk-label'); const infoBtn = h('button.mk-info', { type: 'button', 'aria-label': 'Show device details', title: 'Show device details', onclick: ev => { ev.stopPropagation(); selectDevice(p.dev, { drawer: true }); } }, icon('info'));
    let upd;
    switch (p.type) {
      case 'lamp': {
        const ib = h('span.mk-ico', icon('lampOff')); const t = h('span.t');
        body.append(ib, t);
        upd = q => { setCls(el, 'on', q.on); paintIcon(ib, p.id, pointDefault(p), q.on); setText(t, q.pct != null && q.on ? q.pct + ' %' : ''); return q.on + ':' + q.pct + ':' + (S.icons[p.id]?.n || ''); };
        body.style.cursor = 'var(--cur-pointer)'; body.addEventListener('click', () => { if (!S.planEdit) selectDevice(p.dev, { drawer: true }); });
        break;
      }
      case 'button': {
        const ib = h('span.mk-ico', icon('button'));
        const b = h('button.mk-btn', { type: 'button', 'aria-label': 'Press ' + p.dev, title: 'Press', onclick: () => { if (!S.planEdit) operate('Button press', () => api.act(p.dev, 'press', 0)); } }, ib);
        body.append(b); upd = () => { paintIcon(ib, p.id, pointDefault(p)); return S.icons[p.id]?.n || ''; }; break;
      }
      case 'key': {
        const ib = h('span.mk-ico', icon(p.sym));
        const b = h('button.mk-btn', { type: 'button', 'aria-label': p.name + ' of ' + p.dev, title: p.name + ' (hold for a long press)' }, ib);
        let held = false;
        const up = () => { if (!held) return; held = false; b.classList.remove('down'); operate('Key', () => api.act(p.dev, 'up', p.keyNo)); };
        b.addEventListener('pointerdown', e => { if (S.planEdit || held) return; held = true; b.setPointerCapture(e.pointerId); b.classList.add('down'); operate('Key', () => api.act(p.dev, 'down', p.keyNo)); });
        b.addEventListener('pointerup', up); b.addEventListener('pointercancel', up); b.addEventListener('lostpointercapture', up);
        body.append(b); upd = () => { paintIcon(ib, p.id, pointDefault(p)); return S.icons[p.id]?.n || ''; }; break;
      }
      case 'slider': {
        const r = h('input', { type: 'range', min: 0, max: 100, value: 0, 'aria-label': 'Slider of ' + p.dev }); const t = h('span.t', { style: 'min-width:2.6em;text-align:right' });
        let active = false, until = 0;
        r.addEventListener('pointerdown', () => { active = true; }); const end = () => { active = false; until = Date.now() + 500; };
        r.addEventListener('pointerup', end); r.addEventListener('pointercancel', end);
        r.addEventListener('input', () => { setText(t, r.value + ' %'); api.act(p.dev, 'sliderLive', r.value).catch(() => {}); });
        r.addEventListener('change', () => operate('Slider', () => api.act(p.dev, 'slider', r.value)));
        const ib = h('span.mk-ico', icon('slider'));
        body.append(ib, r, t);
        upd = q => { ib.hidden = !hasCustomIcon(p.id); if (!ib.hidden) paintIcon(ib, p.id, pointDefault(p)); if (!active && Date.now() > until && document.activeElement !== r) { if (+r.value !== q.val) r.value = q.val; setText(t, q.val + ' %'); } return ''; };
        break;
      }
      case 'blind': {
        const w = h('i'); const t = h('span.t'); const win = h('div.mk-win', w); const ib = h('span.mk-ico', icon('blind'));
        body.append(win, ib, t);
        upd = q => { const own = hasCustomIcon(p.id); win.hidden = own; ib.hidden = !own; if (own) paintIcon(ib, p.id, pointDefault(p)); setStyle(w, 'height', q.pos + '%'); setText(t, q.pos + ' %'); body.title = 'Blind position: ' + q.pos + ' % closed'; return q.pos; };
        body.style.cursor = 'var(--cur-pointer)'; body.addEventListener('click', () => { if (!S.planEdit) selectDevice(p.dev, { drawer: true }); });
        break;
      }
      case 'display': {
        const t = h('span.t');
        const ib = h('span.mk-ico', icon('display'));
        body.append(ib, t);
        upd = q => { paintIcon(ib, p.id, pointDefault(p)); setText(t, q.text); return q.text; };
        body.style.cursor = 'var(--cur-pointer)'; body.addEventListener('click', () => { if (!S.planEdit) selectDevice(p.dev, { drawer: true }); });
        break;
      }
      case 'logic': {
        const t = h('span.t');
        const ib = h('span.mk-ico', icon('logic'));
        body.append(ib, t);
        upd = q => { paintIcon(ib, p.id, pointDefault(p)); setText(t, q.text); return q.text; };
        body.style.cursor = 'var(--cur-pointer)'; body.addEventListener('click', () => { if (!S.planEdit) selectDevice(p.dev, { drawer: true }); });
        break;
      }
      case 'contact': {
        const ib = h('span.mk-ico', icon('contact'));
        const b = h('button.mk-btn', { type: 'button', 'aria-label': p.name + ' of ' + p.dev, title: p.name + ': click to close or open the contact' }, ib);
        b.addEventListener('click', () => { if (!S.planEdit) operate('Contact', () => api.act(p.dev, 'toggle', p.keyNo)); });
        const t = h('span.t'); body.append(b, t);
        upd = q => { setCls(el, 'on', q.closed); paintIcon(ib, p.id, pointDefault(p)); setText(t, q.closed ? 'Closed' : 'Open'); return q.closed + ':' + (S.icons[p.id]?.n || ''); };
        break;
      }
      case 'info': {
        const t = h('span.t'); const ib = h('span.mk-ico', icon(p.sym));
        body.append(ib, t);
        upd = q => { setCls(el, 'on', !!q.on); paintIcon(ib, p.id, pointDefault(p)); setText(t, q.text); return q.on + ':' + q.text + ':' + (S.icons[p.id]?.n || ''); };
        body.style.cursor = 'var(--cur-pointer)'; body.addEventListener('click', () => { if (!S.planEdit) selectDevice(p.dev, { drawer: true }); });
        break;
      }
      case 'sensor': {
        const t = h('span.t');
        const ib = h('span.mk-ico', icon(SENSORS[p.sensor].icon));
        body.append(ib, t);
        upd = q => { paintIcon(ib, p.id, pointDefault(p)); setText(t, q.text); return q.text; };
        body.style.cursor = 'var(--cur-pointer)'; body.addEventListener('click', () => { if (!S.planEdit) selectDevice(p.dev, { drawer: true }); });
        break;
      }
    }
    el.append(body, label, infoBtn);
    el._upd = upd; el._label = label;
    el.addEventListener('pointerdown', e => {
      if (!S.planEdit || e.button !== 0) return;
      e.preventDefault(); e.stopPropagation();
      selectMarker(p.id);
      const it = itemOf(p.id); if (!it) return;
      dragging = { id: p.id, it, el }; el.setPointerCapture(e.pointerId);
    });
    el.addEventListener('pointermove', e => {
      if (!dragging || dragging.id !== p.id) return;
      const r = canvas.getBoundingClientRect();
      dragging.it.x = clamp((e.clientX - r.left) / r.width, .01, .99); dragging.it.y = clamp((e.clientY - r.top) / r.height, .01, .99);
      el.style.left = dragging.it.x * 100 + '%'; el.style.top = dragging.it.y * 100 + '%';
    });
    const endDrag = () => { if (dragging && dragging.id === p.id) { dragging = null; save(); renderProps(); } };
    el.addEventListener('pointerup', endDrag); el.addEventListener('pointercancel', endDrag);
    el.addEventListener('keydown', e => {
      if (!S.planEdit || !e.key.startsWith('Arrow')) return;
      const it = itemOf(p.id); if (!it) return; const st = e.shiftKey ? .02 : .005;
      if (e.key === 'ArrowLeft') it.x -= st; if (e.key === 'ArrowRight') it.x += st; if (e.key === 'ArrowUp') it.y -= st; if (e.key === 'ArrowDown') it.y += st;
      it.x = clamp(it.x, .01, .99); it.y = clamp(it.y, .01, .99); el.style.left = it.x * 100 + '%'; el.style.top = it.y * 100 + '%'; save(); e.preventDefault();
    });
    return el;
  }

  function updateMarker(el, p, it) {
    if (!dragging || dragging.id !== p.id) { setStyle(el, 'left', it.x * 100 + '%'); setStyle(el, 'top', it.y * 100 + '%'); }
    const sig = el._upd(p);
    if (el._sig !== undefined && el._sig !== sig && !S.planEdit) { el.classList.remove('flash'); void el.offsetWidth; el.classList.add('flash'); }
    el._sig = sig;
    setText(el._label, it.label || p.label);
    setCls(el, 'edit', S.planEdit); setCls(el, 'sel', S.planEdit && sel === p.id);
    setAttr(el, 'tabindex', S.planEdit ? 0 : null);
    const d = device(p.dev);
    setCls(el, 'hl', !!S.gaSel && !!d && objectsOfPoint(d, p).some(n => (d.assoc || []).some(a => a.obj === n && a.ga === S.gaSel)));
    // in edit mode the controls must not operate
    el.querySelector('.mk-body').style.pointerEvents = '';
    for (const c of el.querySelectorAll('.mk-body input, .mk-body button')) c.style.pointerEvents = S.planEdit ? 'none' : '';
    el.setAttribute('role', 'group'); el.setAttribute('aria-label', (it.label || p.label) + ', ' + p.name);
  }

  function selectMarker(id) { sel = id; update(); }

  // ----- palette -----
  function placeAt(id, x, y, cascade) {
    if (itemOf(id)) return;
    if (cascade) { // several symbols placed from the list must not end up exactly on top of each other
      let n = 0;
      while (layout.items.some(i => Math.abs(i.x - (x + n * .075)) < .03 && Math.abs(i.y - (y + n * .07)) < .03) && n < 10) n++;
      x += n * .075; y += n * .07;
    }
    layout.items.push({ id, x: clamp(x, .02, .98), y: clamp(y, .02, .98) });
    sel = id; save(); update();
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
  function renderProps() {
    const it = sel && itemOf(sel), p = it && pointsMap().get(sel);
    if (!it || !p) {
      props.replaceChildren(); props.dataset.sel = ''; return;
    }
    const d = device(p.dev);
    const key = sel + '|' + (it.label || '');
    if (props.dataset.sel === sel && props.contains(document.activeElement)) { return; } // typing a label: do not rebuild
    props.dataset.sel = sel;
    const label = h('input.input', { type: 'text', value: it.label || '', placeholder: p.label, maxlength: 40, id: 'mk-label', 'aria-describedby': 'mk-label-help' });
    label.addEventListener('input', () => { it.label = label.value.trim() || undefined; save(); const el = layer.querySelector(`[data-id="${CSS.escape(sel)}"]`); if (el) setText(el._label, it.label || p.label); });
    const objs = new Set(objectsOfPoint(d, p));
    const gas = (d.assoc || []).filter(a => objs.has(a.obj));
    const catObjs = S.cat[d.product]?.objects || [];
    props.replaceChildren(
      h('div.props-head', h('div.grow', h('h3', `${p.dev} · ${p.name}`), it.label ? h('div.mute', 'Shown on the plan as “' + it.label + '”') : null), h('button.btn.quiet.icon', { 'aria-label': 'Close properties', title: 'Close', onclick: () => { sel = null; update(); } }, icon('close'))),
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
    layout.items = layout.items.filter(i => i.id !== sel); sel = null; save(); update();
  }

  // ----- interactions on the viewport -----
  viewport.addEventListener('pointerdown', e => {
    if (e.button !== 0 || e.target.closest('.mk')) return;
    if (S.planEdit && sel) { sel = null; update(); }
    panning = { x: e.clientX, y: e.clientY, sl: viewport.scrollLeft, st: viewport.scrollTop };
    viewport.setPointerCapture(e.pointerId); viewport.classList.add('panning');
  });
  viewport.addEventListener('pointermove', e => { if (!panning) return; viewport.scrollLeft = panning.sl - (e.clientX - panning.x); viewport.scrollTop = panning.st - (e.clientY - panning.y); });
  const endPan = () => { panning = null; viewport.classList.remove('panning'); };
  viewport.addEventListener('pointerup', endPan); viewport.addEventListener('pointercancel', endPan);
  viewport.addEventListener('wheel', e => { if (!e.ctrlKey) return; e.preventDefault(); setZoom(layout.zoom * (e.deltaY < 0 ? 1.1 : 1 / 1.1), { x: e.clientX, y: e.clientY }); }, { passive: false });
  new ResizeObserver(() => { if (!layout.zoomSet && hasImg && !root.hidden) fit(); }).observe(viewport);

  async function ensureLoaded() {
    if (loaded) return; loaded = true;
    try { const l = await api.layout(); layout = { items: [], zoom: 100, size: 1, ...l }; if (!layout.items) layout.items = []; } catch (e) { toast(`The floor plan layout could not be loaded: ${e.message}`, 'error'); loaded = false; return; }
    if (layout.zoom) layout.zoomSet = layout.zoom !== 100 || undefined;
    applyZoom(); applySize(); planKnown = !!S.state?.plan; if (planKnown) loadImg(); else img.onerror();
  }

  function update() {
    if (root.parentElement && root.hidden) return;
    ensureLoaded();
    const hp = !!S.state?.plan;
    if (loaded && hp !== planKnown) { planKnown = hp; if (hp) loadImg(); else img.onerror(); }
    bOp.setAttribute('aria-pressed', String(!S.planEdit)); bEd.setAttribute('aria-pressed', String(S.planEdit));
    palette.hidden = !S.planEdit; props.hidden = !(S.planEdit && sel);
    refreshTools();
    const pts = pointsMap();
    const items = layout.items.filter(i => pts.has(i.id));
    syncKeyed(layer, items, i => i.id, i => createMarker(pts.get(i.id)), (el, i) => updateMarker(el, pts.get(i.id), i));
    if (S.planEdit) { renderPalette(); renderProps(); }
  }
  on('state', () => { if (!root.hidden) update(); });
  on('icons', () => { if (!root.hidden) update(); });
  on('devices', () => { if (!root.hidden) update(); });
  on('ga', () => { if (!root.hidden) update(); });
  return { el: root, update: () => { root.hidden = false; update(); } };
}
