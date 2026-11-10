import { h, icon, setText, setCls, setAttr, syncKeyed } from '../util.js';
import { S, on, selectDevice, hooks } from '../bus.js';
import { productOf, statusOf } from '../devinfo.js';
import { createDetail, renameDevice } from '../detail.js';
import { openAddDevice } from '../addDevice.js';
import { renderGuide, createStatusStrip, createTopologyDiagram, openEtsMessages } from '../guide.js';
import { paintIcon } from '../iconStore.js';

export function createInstallation() {
  const root = h('div.view'); const split = h('div.split'); const strip = createStatusStrip(); const diagram = createTopologyDiagram();
  root.append(strip.el, split);
  const search = h('input.input', { type: 'search', placeholder: 'Search devices', 'aria-label': 'Search devices' });
  const list = h('div.list', { role: 'list' });
  const tree = h('div.list.topo', { hidden: true });
  let mode = 'list';
  const modeBtns = { list: h('button', { type: 'button', onclick: () => { mode = 'list'; update(); } }, 'Devices'), topology: h('button', { type: 'button', title: 'Show the devices as a topology tree (areas, lines)', onclick: () => { mode = 'topology'; update(); } }, 'Topology') };
  const modeSeg = h('div.segmented.full', { role: 'group', 'aria-label': 'Device view' }, modeBtns.list, modeBtns.topology);
  hooks.showTopology = () => { mode = 'topology'; update(); };
  const count = h('span.hint');
  const empty = h('div.empty', icon('devices'), h('div', 'No devices yet.'), h('div.hint', 'Use "Add device" to create one from the catalogue.'));
  split.append(
    h('section.panel.list-panel', h('div.list-tools', h('label.search', icon('search'), search),
      h('button.btn.primary', { onclick: openAddDevice }, icon('plus'), 'Add device'), modeSeg), list, tree, h('div', { style: 'padding:.5rem .85rem;border-top:1px solid var(--border)' }, count)));
  const overviewDiagram = createTopologyDiagram();
  const ov = h('div.overview'); let ovSig = '';
  const overview = { el: ov, update() {
    overviewDiagram.update();
    const ds = S.state?.devices || [];
    const next = ds.map(d => ({ d, g: renderGuide(d) })).find(x => !x.g.ready);
    const sig = next ? next.d.name + next.g.sig : 'none:' + ds.length;
    if (sig === ovSig && ov.firstChild) return; ovSig = sig;
    ov.replaceChildren(
      next ? h('div.next-bar.now', icon('chevron'), h('span.grow', h('b', 'Next: '), next.g.next ? next.g.next.short : 'finish ' + next.d.name, h('span.mute', ' · ' + next.d.name)),
        h('button.btn.sm', { onclick: () => selectDevice(next.d.name) }, 'Open ' + next.d.name))
        : ds.length ? h('div.next-bar.ok', icon('check'), h('span.grow', h('b', 'All devices are ready. '), 'Select a device for its details, or go to Simulation to operate them.'))
        : h('div.empty', icon('devices'), 'Add a device to get started.'),
      h('details.fold', { open: true }, h('summary', 'Topology'), overviewDiagram.el),
      next && next.g.next && next.g.next.key === 'ets' ? h('details.fold', h('summary', 'Need help connecting ETS?'), h('p.hint', { style: 'margin-top:.4rem' }, next.g.next.todo), h('button.btn.sm', { onclick: openEtsMessages }, icon('info'), 'ETS messages explained')) : null);
    overviewDiagram.el.dataset.mounted = '1';
  } };
  const detail = createDetail({ onBack: () => selectDevice(null), empty: overview });
  const detailPanel = h('section.panel.detail-panel', detail.el);
  split.append(detailPanel);
  search.addEventListener('input', update);

  function matches(d, q) {
    if (!q) return true;
    const info = productOf(d);
    return [d.name, info.title, d.productName, d.address].some(s => String(s).toLowerCase().includes(q));
  }

  /** Pencil next to a device: rename it. A sibling of the row button, because a button cannot sit inside a button. */
  const renameBtn = name => h('button.btn.quiet.icon.row-rename', { type: 'button', 'aria-label': `Rename ${name}`, title: 'Rename device', onclick: ev => { ev.stopPropagation(); renameDevice(name); } }, icon('pencil'));

  function create(d) {
    const info = productOf(d);
    const b = h('div.row', { role: 'listitem', onclick: () => selectDevice(d.name) },
      h('button.row-main', { type: 'button', onclick: () => selectDevice(d.name) },
        h('div.ico', icon(info.icon)),
        h('div.grow', h('div.name.ellipsis', d.name), h('div.sub.ellipsis', info.title)),
        h('div', { style: 'text-align:right' }, h('div.mono.addr'), h('span.chip.st'))),
      renameBtn(d.name));
    return b;
  }
  function updRow(el, d) {
    const st = statusOf(d);
    paintIcon(el.querySelector('.ico'), d.name, productOf(d).icon);
    setText(el.querySelector('.name'), d.name);
    setText(el.querySelector('.addr'), d.unassigned ? '–' : d.address);
    const chip = el.querySelector('.st');
    setText(chip, d.programming ? 'Programming' : st.label); chip.className = 'chip st ' + (d.programming ? 'prog' : st.cls);
    setAttr(el, 'aria-current', S.selected === d.name ? 'true' : null);
    setCls(el, 'hl', !!S.gaSel && (d.assoc || []).some(a => a.ga === S.gaSel));
  }

  function renderTree(ds) {
    const byLine = new Map(), unplaced = [];
    for (const d of ds) { if (d.unassigned) { unplaced.push(d); continue; } if (!byLine.has(d.line)) byLine.set(d.line, []); byLine.get(d.line).push(d); }
    const lines = [...byLine.keys()].sort((a, b) => { const [x, y] = a.split('.').map(Number), [p, q] = b.split('.').map(Number); return x - p || y - q; });
    const areas = new Map();
    for (const l of lines) { const a = l.split('.')[0]; if (!areas.has(a)) areas.set(a, []); areas.get(a).push(l); }
    const dev = d => h('div.row', { onclick: () => selectDevice(d.name), 'aria-current': S.selected === d.name ? 'true' : null },
      h('button.row-main', { type: 'button', onclick: () => selectDevice(d.name) },
        (() => { const b = h('div.ico'); paintIcon(b, d.name, productOf(d).icon); return b; })(), h('div.grow', h('div.name.ellipsis', d.name), h('div.sub.ellipsis', productOf(d).title + (d.location ? ' · wired by hand' : ''))),
        h('div.mono', d.address)),
      renameBtn(d.name));
    diagram.update();
    const kids = [h('div', { style: 'padding:.4rem .85rem' }, diagram.el), h('p.hint', { style: 'margin:.2rem .85rem' }, 'Devices are placed by their physical address as soon as ETS assigns it (a line coupler x.y.0 sits on line x.y, an area coupler x.0.0 on main line x.0). Telegrams only cross between lines through couplers.')];
    for (const [a, ls] of areas) {
      kids.push(h('h4', { style: 'margin:.8rem .85rem .2rem' }, a === '0' ? 'Backbone (area 0)' : `Area ${a}`));
      for (const l of ls) {
        const [, n] = l.split('.');
        kids.push(h('div.mute', { style: 'margin:.3rem .85rem 0' }, n === '0' ? `Main line ${l}` : `Line ${l}`));
        kids.push(...byLine.get(l).sort((x, y) => x.address.localeCompare(y.address, undefined, { numeric: true })).map(dev));
      }
    }
    if (unplaced.length) { kids.push(h('h4', { style: 'margin:.8rem .85rem .2rem' }, 'Not addressed yet'), ...unplaced.map(dev)); }
    if (!ds.length) kids.push(h('div.empty', 'No devices yet.'));
    tree.replaceChildren(...kids);
  }

  function update() {
    const ds = S.state?.devices || [];
    const q = search.value.trim().toLowerCase();
    const shown = ds.filter(d => matches(d, q));
    tree.hidden = mode !== 'topology'; list.hidden = mode === 'topology'; for (const [k, b] of Object.entries(modeBtns)) setAttr(b, 'aria-pressed', String(mode === k));
    if (mode === 'topology') renderTree(shown);
    syncKeyed(list, shown, d => d.name, create, updRow);
    if (!shown.length) { if (!empty.isConnected) list.append(empty); } else empty.remove();
    setText(count, q ? `${shown.length} of ${ds.length} devices` : `${ds.length} device${ds.length === 1 ? '' : 's'}`);
    if (S.selected && !ds.some(d => d.name === S.selected)) S.selected = null;
    setCls(split, 'has-sel', !!S.selected);
    strip.update();
    detail.update();
  }
  const live = () => { if (S.view === 'installation') update(); };
  on('state', live); on('select', live); on('ga', live); on('devices', live); on('telegrams', live); on('icons', live);
  return { el: root, update };
}
