import { h, icon, setCls, setPref, syncKeyed, clamp } from '../util.js';
import { S, on, emit, selectDevice, navigate } from '../bus.js';
import { createCard } from '../cards.js';
import { createDetail, deviceMenu } from '../detail.js';
import { openAddDevice } from '../addDevice.js';
import { createMonitor } from './monitor.js';
import { createPlan } from './plan.js';

export function createSimulation() {
  const root = h('div.view');
  const seg = h('div.segmented', { role: 'group', 'aria-label': 'Simulation view' });
  const bCards = h('button', { type: 'button', onclick: () => setMode('cards') }, icon('devices'), 'Operating cards');
  const bPlan = h('button', { type: 'button', onclick: () => setMode('plan') }, icon('plan'), 'Floor plan');
  seg.append(bCards, bPlan);
  const bMon = h('button.btn', { type: 'button', 'aria-pressed': 'false', onclick: () => toggleDock() }, icon('devices'), h('span.btn-text', 'Telegram monitor'));
  const toolbar = h('div.toolbar', seg, h('span.grow'), h('button.btn', { onclick: openAddDevice }, icon('plus'), h('span.btn-text.opt', 'Add device')), bMon);
  const cards = h('div.cards');
  const scroll = h('div.cards-scroll', cards);
  const emptyMsg = h('div.empty', icon('devices'), h('div', 'No devices to operate yet.'), h('div.hint', 'Add devices in Installation or with "Add device".'));
  const plan = createPlan();
  const content = h('div', { style: 'flex:1;min-height:0;display:flex;flex-direction:column' }, scroll, plan.el);
  const dockMon = createMonitor({ compact: true });
  const handle = h('div.dock-handle', { role: 'separator', 'aria-orientation': 'horizontal', 'aria-label': 'Resize the monitor', tabindex: 0, title: 'Drag to resize' });
  const dock = h('div.dock', handle,
    h('div.dock-head', icon('devices'), 'Telegram monitor', h('span.grow'),
      h('button.btn.quiet.sm', { onclick: () => navigate('monitor') }, 'Open full monitor'),
      h('button.btn.quiet.icon.sm', { 'aria-label': 'Hide the monitor', title: 'Hide the monitor', onclick: () => toggleDock(false) }, icon('close'))),
    dockMon.el);
  const main = h('div.sim-main', content, dock);
  const drawerDetail = createDetail({ onClose: () => { S.drawer = false; emit('select'); } });
  const drawer = h('aside.drawer', { 'aria-label': 'Device details' }, drawerDetail.el);
  root.append(toolbar, h('div.sim-body', main, drawer));

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
    if (S.simMode === 'cards') {
      syncKeyed(cards, ds, d => d.name, d => { const c = createCard(d, { menu: deviceMenu }); cardOf.set(c.el, c); return c.el; }, (el, d) => {
        cardOf.get(el).update(d);
        setCls(el, 'hl', !!S.gaSel && (d.assoc || []).some(a => a.ga === S.gaSel));
      });
      if (!ds.length) { if (!emptyMsg.isConnected) scroll.append(emptyMsg); } else emptyMsg.remove();
    } else plan.update();
    if (!drawer.hidden) drawerDetail.update();
  }
  on('state', update); on('select', update); on('ga', update); on('dock', update); on('devices', update);
  window.addEventListener('resize', () => { if (S.dockOpen && S.view === 'simulation') applyDockHeight(); });
  return { el: root, update, onShow: () => { update(); dockMon.update(); } };
}
