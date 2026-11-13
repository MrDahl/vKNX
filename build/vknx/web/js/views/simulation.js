import { h, icon, setCls, setText, setPref, pref, syncKeyed, clamp } from '../util.js';
import { productOf } from '../devinfo.js';
import { S, on, emit, selectDevice, navigate } from '../bus.js';
import { createCard } from '../cards.js';
import { createDetail, deviceMenu, renameDevice } from '../detail.js';
import { openAddDevice } from '../addDevice.js';
import { createMonitor } from './monitor.js';
import { createPlan } from './plan.js';

export function createSimulation() {
  const root = h('div.view');
  const seg = h('div.segmented', { role: 'group', 'aria-label': 'Simulation view' });
  const bCards = h('button', { type: 'button', onclick: () => setMode('cards') }, icon('devices'), 'Operating cards');
  const bPlan = h('button', { type: 'button', onclick: () => setMode('plan') }, icon('plan'), 'Floor plan');
  seg.append(bCards, bPlan);
  const bMon = h('button.btn', { type: 'button', 'aria-pressed': 'false', onclick: () => toggleDock() }, icon('devices'), h('span.btn-text', 'Monitor'));
  bMon.title = 'Show the telegram monitor below (the same as the Monitor tab)';
  // operating cards: search, filter by kind of product, compact cards
  let compact = pref('cardsCompact', false), cat = '';
  const cardSearch = h('input.input', { type: 'search', placeholder: 'Search cards', 'aria-label': 'Search operating cards', style: 'width:11rem' });
  const catSel = h('select.select', { 'aria-label': 'Show only one kind of device' });
  const bCompact = h('button.btn', { type: 'button', 'aria-pressed': String(compact), title: 'Compact cards: only what you operate, without "How to operate" and the group addresses',
    onclick: () => { compact = !compact; setPref('cardsCompact', compact); update(); } }, icon('devices'), h('span.btn-text.opt', 'Compact'));
  const cardTools = h('span.card-tools', h('label.search', icon('search'), cardSearch), catSel, bCompact);
  const shownNote = h('span.hint');
  cardSearch.addEventListener('input', () => update()); catSel.addEventListener('change', () => { cat = catSel.value; update(); });
  const toolbar = h('div.toolbar', seg, cardTools, shownNote, h('span.grow'), h('button.btn', { onclick: openAddDevice }, icon('plus'), h('span.btn-text.opt', 'Add device')), bMon);
  const cards = h('div.cards');
  const scroll = h('div.cards-scroll', cards);
  const emptyMsg = h('div.empty', icon('devices'), h('div', 'No devices to operate yet.'), h('div.hint', 'Add devices in Installation or with "Add device".'));
  const noMatch = h('div.empty', icon('search'), h('div', 'No card matches the search or the filter.'));
  const plan = createPlan();
  const content = h('div', { style: 'flex:1;min-height:0;display:flex;flex-direction:column' }, scroll, plan.el);
  const dockMon = createMonitor({ compact: true });
  const handle = h('div.dock-handle', { role: 'separator', 'aria-orientation': 'horizontal', 'aria-label': 'Resize the monitor', tabindex: 0, title: 'Drag to resize' });
  const dock = h('div.dock', handle,
    h('div.dock-head', icon('devices'), 'Monitor', h('span.grow'),
      h('button.btn.quiet.sm', { onclick: () => navigate('monitor') }, 'Open full monitor'),
      h('button.btn.quiet.icon.sm', { 'aria-label': 'Hide the monitor', title: 'Hide the monitor', onclick: () => toggleDock(false) }, icon('close'))),
    dockMon.el);
  const main = h('div.sim-main', content, dock);
  const drawerDetail = createDetail({ onClose: () => { S.drawer = false; emit('select'); } });
  const drawer = h('aside.drawer', { 'aria-label': 'Device details' }, drawerDetail.el);
  root.append(toolbar, h('div.sim-body', main, drawer));
  // Esc closes the device details, unless a dialog, menu or text field is using the key
  document.addEventListener('keydown', e => {
    if (e.key !== 'Escape' || drawer.hidden || root.offsetParent === null || e.defaultPrevented) return;
    if (document.querySelector('dialog[open]') || document.querySelector('[role=menu]')) return;
    const a = document.activeElement; if (a && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName)) return;
    S.drawer = false; emit('select');
  });

  function setMode(m) { S.simMode = m; setPref('simMode', m); update(); }
  function toggleDock(v) { S.dockOpen = v === undefined ? !S.dockOpen : v; setPref('dockOpen', S.dockOpen); update(); }
  function applyDockHeight() { const max = Math.max(160, main.clientHeight * 0.7); dock.style.height = clamp(S.dockHeight, 120, max) + 'px'; }

  let drag = null;
  handle.addEventListener('pointerdown', e => { drag = { y: e.clientY, h: dock.offsetHeight }; handle.setPointerCapture(e.pointerId); });
  handle.addEventListener('pointermove', e => { if (!drag) return; S.dockHeight = drag.h + (drag.y - e.clientY); applyDockHeight(); });
  const endDrag = () => { if (drag) { drag = null; setPref('dockHeight', S.dockHeight); } };
  handle.addEventListener('pointerup', endDrag); handle.addEventListener('pointercancel', endDrag);
  handle.addEventListener('keydown', e => { if (e.key === 'ArrowUp' || e.key === 'ArrowDown') { S.dockHeight += e.key === 'ArrowUp' ? 30 : -30; applyDockHeight(); setPref('dockHeight', S.dockHeight); e.preventDefault(); } });

  const cardOf = new WeakMap();
  function update() {
    const ds = S.state?.devices || [];
    bCards.setAttribute('aria-pressed', String(S.simMode === 'cards')); bPlan.setAttribute('aria-pressed', String(S.simMode === 'plan'));
    bMon.setAttribute('aria-pressed', String(S.dockOpen)); setCls(bMon, 'on', S.dockOpen);
    scroll.hidden = S.simMode !== 'cards'; plan.el.hidden = S.simMode !== 'plan';
    dock.hidden = !S.dockOpen;
    if (S.dockOpen) applyDockHeight();
    drawer.hidden = !(S.drawer && S.selected);
    cardTools.hidden = S.simMode !== 'cards'; shownNote.hidden = S.simMode !== 'cards';
    if (S.simMode === 'cards') {
      const cats = [...new Set(ds.map(d => productOf(d).category).filter(Boolean))].sort();
      if (catSel.dataset.sig !== cats.join('|')) {
        catSel.dataset.sig = cats.join('|');
        catSel.replaceChildren(h('option', { value: '' }, 'All kinds'), ...cats.map(c => h('option', { value: c }, c + 's')));
        if (cat && !cats.includes(cat)) cat = '';
        catSel.value = cat;
      }
      const q = cardSearch.value.trim().toLowerCase();
      const shown = ds.filter(d => (!cat || productOf(d).category === cat) && (!q || [d.name, productOf(d).title, d.address, ...Object.values(d.view || {}).filter(x => typeof x === 'string')].some(t => String(t).toLowerCase().includes(q))));
      setCls(cards, 'compact', compact); bCompact.setAttribute('aria-pressed', String(compact)); setCls(bCompact, 'on', compact);
      setText(shownNote, shown.length !== ds.length ? `${shown.length} of ${ds.length}` : '');
      const none = ds.length && !shown.length;
      if (none) { if (!noMatch.isConnected) scroll.append(noMatch); } else noMatch.remove();
      syncKeyed(cards, shown, d => d.name, d => { const c = createCard(d, { menu: deviceMenu, rename: renameDevice }); cardOf.set(c.el, c); return c.el; }, (el, d) => {
        cardOf.get(el).update(d);
        setCls(el, 'hl', !!S.gaSel && (d.assoc || []).some(a => a.ga === S.gaSel));
      });
      if (!ds.length) { if (!emptyMsg.isConnected) scroll.append(emptyMsg); } else emptyMsg.remove();
    } else plan.update();
    if (!drawer.hidden) drawerDetail.update();
  }
  const live = () => { if (S.view === 'simulation') update(); };
  on('state', live); on('select', live); on('ga', live); on('dock', live); on('devices', live); on('icons', live);
  window.addEventListener('resize', () => { if (S.dockOpen && S.view === 'simulation') applyDockHeight(); });
  return { el: root, update, onShow: () => { update(); dockMon.update(); } };
}
