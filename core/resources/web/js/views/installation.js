import { h, icon, setText, setCls, setAttr, syncKeyed } from '../util.js';
import { S, on, selectDevice } from '../bus.js';
import { productOf, statusOf } from '../devinfo.js';
import { createDetail } from '../detail.js';
import { openAddDevice } from '../addDevice.js';

export function createInstallation() {
  const root = h('div.split');
  const search = h('input.input', { type: 'search', placeholder: 'Search devices', 'aria-label': 'Search devices' });
  const list = h('div.list', { role: 'list' });
  const count = h('span.hint');
  const empty = h('div.empty', icon('devices'), h('div', 'No devices yet.'), h('div.hint', 'Use "Add device" to create one from the catalogue.'));
  root.append(
    h('section.panel.list-panel', h('div.list-tools', h('label.search', icon('search'), search),
      h('button.btn.primary', { onclick: openAddDevice }, icon('plus'), 'Add device')), list, h('div', { style: 'padding:.5rem .85rem;border-top:1px solid var(--border)' }, count)));
  const detail = createDetail({ onBack: () => selectDevice(null) });
  const detailPanel = h('section.panel.detail-panel', detail.el);
  root.append(detailPanel);
  search.addEventListener('input', update);

  function matches(d, q) {
    if (!q) return true;
    const info = productOf(d);
    return [d.name, info.title, d.productName, d.address].some(s => String(s).toLowerCase().includes(q));
  }

  function create(d) {
    const info = productOf(d);
    const b = h('button.row', { type: 'button', role: 'listitem', onclick: () => selectDevice(d.name) },
      h('div.ico', icon(info.icon)),
      h('div.grow', h('div.name.ellipsis', d.name), h('div.sub.ellipsis', info.title)),
      h('div', { style: 'text-align:right' }, h('div.mono.addr'), h('span.chip.st')));
    return b;
  }
  function updRow(el, d) {
    const st = statusOf(d);
    setText(el.querySelector('.name'), d.name);
    setText(el.querySelector('.addr'), d.unassigned ? '–' : d.address);
    const chip = el.querySelector('.st');
    setText(chip, d.programming ? 'Programming' : st.label); chip.className = 'chip st ' + (d.programming ? 'prog' : st.cls);
    setAttr(el, 'aria-current', S.selected === d.name ? 'true' : null);
    setCls(el, 'hl', !!S.gaSel && (d.assoc || []).some(a => a.ga === S.gaSel));
  }

  function update() {
    const ds = S.state?.devices || [];
    const q = search.value.trim().toLowerCase();
    const shown = ds.filter(d => matches(d, q));
    syncKeyed(list, shown, d => d.name, create, updRow);
    if (!shown.length) { if (!empty.isConnected) list.append(empty); } else empty.remove();
    setText(count, q ? `${shown.length} of ${ds.length} devices` : `${ds.length} device${ds.length === 1 ? '' : 's'}`);
    if (S.selected && !ds.some(d => d.name === S.selected)) S.selected = null;
    setCls(root, 'has-sel', !!S.selected);
    detail.update();
  }
  on('state', update); on('select', update); on('ga', update); on('devices', update);
  return { el: root, update };
}
