import { h, icon, debounce } from './util.js';
import { S, device } from './bus.js';
import { modal, toast } from './dialogs.js';
import { ICON_SETS } from './iconSets.js';
import { pointsOf, productOf } from './devinfo.js';
import { paintIcon, setIcon, hasCustomIcon, pointDefault, pointKind } from './iconStore.js';

/** The full Material Design Icons list, loaded once and only when a picker is opened (about 2.7 MB). */
let catalog = null;
function loadCatalog() {
  catalog ||= fetch('/web/mdi-all.json').then(r => { if (!r.ok) throw new Error('the icon list could not be loaded'); return r.json(); }).then(j => {
    const byName = new Map();
    for (const [n, p, a, ha] of j.icons) byName.set(n, { n, p, a, ha, hay: (n.replace(/-/g, ' ') + ' ' + a).toLowerCase() });
    return { list: [...byName.values()], byName, version: j.version };
  }).catch(e => { catalog = null; throw e; });
  return catalog;
}

/** The off variant of a light icon, if MDI has one: lightbulb -> lightbulb-off, ceiling-light -> ceiling-light-outline. */
function offVariant(name, byName) {
  const cands = name.endsWith('-on') ? [name.slice(0, -3) + '-off', name.slice(0, -3) + '-outline'] : [name + '-off', name + '-outline'];
  for (const c of cands) if (byName.has(c)) return byName.get(c);
  return null;
}

const svgOf = d => { const t = document.createElement('template'); t.innerHTML = `<svg class="ic" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="${d}"/></svg>`; return t.content.firstChild; };

/**
 * Icon picker. kind: key of ICON_SETS (what is suggested first). Resolves with { n, p, o? } for a choice,
 * null for "use the built-in icon", or undefined when cancelled.
 */
export async function pickIcon({ title, kind = 'device', currentName = null, withOff = false }) {
  let cat;
  try { cat = await loadCatalog(); } catch (e) { toast('The icon catalogue could not be opened: ' + e.message, 'error'); return undefined; }
  return new Promise(resolve => {
    const set = ICON_SETS[kind] || ICON_SETS.device;
    let tab = 'suggested', chosen = currentName && cat.byName.get(currentName) || null, result;
    const search = h('input.input', { type: 'search', placeholder: 'Search all icons, for example "ceiling" or "blind"', 'aria-label': 'Search icons', style: 'flex:1;min-width:12rem' });
    const seg = h('div.segmented', { role: 'group', 'aria-label': 'Icon list' });
    const TABS = [['suggested', set.label], ['ha', 'Home automation'], ['all', 'All icons']];
    const tabBtns = TABS.map(([k, t]) => h('button', { type: 'button', onclick: () => { tab = k; draw(); } }, t));
    seg.append(...tabBtns);
    const grid = h('div.ipick-grid', { role: 'group', 'aria-label': 'Icons' });
    const note = h('div.hint', { style: 'min-height:1.3em' });
    const prevOn = h('div.ipick-prev.on'), prevOff = h('div.ipick-prev'), prevName = h('div.mono', { style: 'min-height:1.3em' });
    const preview = h('div.ipick-side', h('div.hint', 'Preview'), h('div', { style: 'display:flex;gap:.6rem;align-items:center' }, prevOn, withOff ? prevOff : null), prevName,
      withOff ? h('div.hint', 'On and off. Where the catalogue has a matching "off" or "outline" icon it is used for the off state.') : h('div.hint', 'Click an icon to choose it, double-click to choose and close.'));
    const ok = h('button.btn.primary', { disabled: !chosen }, icon('check'), 'Use this icon');
    const def = h('button.btn', { type: 'button' }, 'Use the built-in icon');

    function choose(ic) {
      chosen = ic;
      for (const b of grid.children) b.setAttribute('aria-pressed', String(b.title === ic.n));
      paintPreview(); ok.disabled = false;
    }
    function paintPreview() {
      if (!chosen) { prevOn.replaceChildren(); prevOff.replaceChildren(); prevName.textContent = ''; return; }
      prevOn.replaceChildren(svgOf(chosen.p)); prevName.textContent = chosen.n;
      const off = withOff ? offVariant(chosen.n, cat.byName) : null;
      prevOff.replaceChildren(svgOf((off || chosen).p)); prevOff.classList.toggle('dim-off', !off);
    }
    function button(ic) {
      return h('button.ipick', { type: 'button', title: ic.n, 'aria-pressed': String(chosen?.n === ic.n), onclick: () => choose(ic), ondblclick: () => { choose(ic); ok.click(); } }, svgOf(ic.p));
    }
    function list() {
      const q = search.value.trim().toLowerCase();
      if (q) {
        const toks = q.split(/\s+/);
        const hit = cat.list.filter(i => toks.every(t => i.hay.includes(t)));
        hit.sort((a, b) => (a.n.startsWith(toks[0]) ? 0 : 1) - (b.n.startsWith(toks[0]) ? 0 : 1) || a.n.length - b.n.length);
        return { items: hit, text: `${hit.length} icon${hit.length === 1 ? '' : 's'} match "${q}"` };
      }
      if (tab === 'suggested') return { items: set.names.map(n => cat.byName.get(n)).filter(Boolean), text: `Suggested for this: ${set.names.length} icons. Search to find others.` };
      if (tab === 'ha') { const l = cat.list.filter(i => i.ha); return { items: l, text: `${l.length} home automation icons` }; }
      return { items: cat.list, text: `${cat.list.length} icons (Material Design Icons ${cat.version})` };
    }
    function draw() {
      tabBtns.forEach((b, i) => b.setAttribute('aria-pressed', String(TABS[i][0] === tab && !search.value.trim())));
      const { items, text } = list();
      const MAX = 400;
      grid.replaceChildren(...items.slice(0, MAX).map(button));
      note.textContent = text + (items.length > MAX ? `. Showing the first ${MAX}: type in the search box to narrow it down.` : '');
      if (!items.length) grid.replaceChildren(h('div.empty', 'No icon matches. Try an English word such as "lamp", "door" or "window".'));
    }
    search.addEventListener('input', debounce(draw, 120));
    ok.onclick = () => {
      if (!chosen) return;
      const off = withOff ? offVariant(chosen.n, cat.byName) : null;
      result = { n: chosen.n, p: chosen.p, ...(off ? { o: off.p } : {}) };
      dlg.close();
    };
    def.onclick = () => { result = null; dlg.close(); };
    const dlg = modal({
      title, wide: true,
      body: [h('div', { style: 'display:flex;gap:.6rem;flex-wrap:wrap;align-items:center;margin-bottom:.6rem' }, search, seg), grid, note, preview,
        h('p.hint', { style: 'margin:.6rem 0 0' }, 'Icons: Material Design Icons by Pictogrammers (Apache-2.0), https://pictogrammers.com/library/mdi/')],
      buttons: close => [h('button.btn', { onclick: close }, 'Cancel'), def, ok],
    });
    dlg.addEventListener('close', () => resolve(result));
    draw(); paintPreview(); search.focus();
  });
}

/** Dialog with the icon of a device and of each of its channels; each can be changed or reset. */
export function openIconEditor(devName) {
  const d = device(devName); if (!d) return;
  const info = productOf(d);
  const rows = [{ id: d.name, label: 'Whole device', sub: 'Shown in lists, on the card and in the details', def: info.icon, kind: 'device', off: false }];
  for (const p of pointsOf(d)) rows.push({ id: p.id, label: p.name, sub: pointKind(p) === 'light' ? 'Lamp: has an off variant' : '', def: pointDefault(p), kind: pointKind(p), off: p.type === 'lamp' });
  const body = h('div');
  function draw() {
    body.replaceChildren(h('table.tbl', h('tbody', rows.map(r => {
      const on = h('span.ico-box'), off = r.off ? h('span.ico-box') : null;
      const paint = () => { paintIcon(on, r.id, r.def, true); if (off) paintIcon(off, r.id, r.def, false); };
      paint();
      return h('tr', h('td', { style: 'width:5.5rem;white-space:nowrap' }, on, off), h('td', h('b', r.label), r.sub ? h('div.hint', r.sub) : null),
        h('td', { style: 'white-space:nowrap;text-align:right' },
          h('button.btn.sm', { onclick: async () => {
            const res = await pickIcon({ title: `Icon for ${d.name}: ${r.label}`, kind: r.kind, currentName: S.icons[r.id]?.n, withOff: r.off });
            if (res === undefined) return;
            await setIcon(r.id, res); draw();
          } }, 'Change…'),
          hasCustomIcon(r.id) ? h('button.btn.sm.quiet', { style: 'margin-left:.3rem', onclick: async () => { await setIcon(r.id, null); draw(); } }, 'Reset') : null));
    }))), h('p.hint', 'Pick any Material Design icon. The choice is saved with the installation and used on the cards, in the lists and on the floor plan.'));
  }
  draw();
  modal({ title: `Icons for ${d.name}`, wide: true, body, buttons: close => [h('button.btn.primary', { onclick: close }, 'Done')] });
}
