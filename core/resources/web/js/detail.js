import { h, icon, setText, setCls, setAttr, naturalCmp } from './util.js';
import { api } from './api.js';
import { S, emit, device, showTelegrams, showGroupAddress, selectDevice, hooks } from './bus.js';
import { statusOf, productOf, DOWNLOAD_TEXT } from './devinfo.js';
import { confirmDialog, menuButton, toast } from './dialogs.js';
import { operate } from './cards.js';

const openGroups = new Set();

export async function removeDevice(name) {
  const ok = await confirmDialog({
    title: 'Remove device', danger: true, confirmLabel: 'Remove device',
    message: `Remove "${name}"? Its address, downloaded application and saved state are deleted. ETS will see the device disappear from the line. This cannot be undone.`,
  });
  if (!ok) return;
  if (await operate(`Removing "${name}"`, () => api.remove(name))) {
    toast(`"${name}" was removed.`, 'ok');
    if (S.selected === name) selectDevice(null, { drawer: false });
    await hooks.refresh();
    emit('devices');
  }
}

export function deviceMenu(name) {
  return menuButton({
    label: `More actions for ${name}`,
    items: () => [
      { label: 'Show details', icon: 'info', onclick: () => { selectDevice(name, { drawer: true }); } },
      { label: 'Show telegrams for this device', icon: 'devices', onclick: () => showTelegrams(name) },
      'hr',
      { label: 'Remove device…', icon: 'trash', danger: true, onclick: () => removeDevice(name) },
    ],
  });
}

/** Device details: product, address, programming, status, ETS parameters, objects and group addresses. */
export function createDetail({ onBack, onClose } = {}) {
  const root = h('div.detail');
  const head = h('div.detail-head');
  const body = h('div');
  root.append(head, body);
  let current = null;
  const els = {};

  function buildFor(name) {
    current = name;
    const d = device(name); if (!d) return;
    const info = productOf(d);
    head.replaceChildren(
      onBack ? h('button.btn.quiet.back-only', { 'aria-label': 'Back to the device list', onclick: onBack }, icon('back'), 'Devices') : null,
      h('div.ico', icon(info.icon)),
      h('div.grow', h('h2.ellipsis', d.name), h('div.mute.ellipsis', `${info.title} · ${d.productName}`)),
      els.chip = h('span.chip'),
      deviceMenu(name),
      onClose ? h('button.btn.quiet.icon', { 'aria-label': 'Close details', title: 'Close details', onclick: onClose }, icon('close')) : null);
    els.progBtn = h('button.prog-btn', { 'aria-pressed': 'false', onclick: () => operate('Programming button', () => api.prog(name)) }, h('span.led'), h('span.t', 'Programming button'));
    els.progTxt = h('div.hint');
    els.progBox = h('div.prog-box', els.progBtn, els.progTxt);
    els.addr = h('span'); els.app = h('span'); els.dl = h('span'); els.dlN = h('span');
    const check = (label, valueEl) => h('li', h('span.label', label), valueEl);
    els.checks = h('ul.checks', check('Physical address', els.addr), check('Application', els.app), check('Download', els.dl), check('Downloads received', els.dlN));
    els.params = h('div'); els.objs = h('div.tbl-wrap');
    els.sigParams = ''; els.sigObjs = '';
    body.replaceChildren(
      section('Programming and status', h('div', els.progBox, els.checks)),
      section('ETS parameters', els.params),
      section('Communication objects and group addresses', els.objs),
      section('Telegrams', h('div', h('p.hint', { style: 'margin-top:0' }, 'Telegrams sent by this device or addressed to one of its group addresses.'),
        h('button.btn', { onclick: () => showTelegrams(name) }, icon('devices'), 'Show telegrams for this device'))));
  }
  function section(title, content) { return h('section.section', h('header', h('h3', title)), h('div.body', content)); }

  function update() {
    const d = device(S.selected);
    if (!d) { current = null; head.replaceChildren(); body.replaceChildren(h('div.empty', icon('devices'), 'Select a device to see its details.')); return; }
    if (current !== d.name) buildFor(d.name);
    const st = statusOf(d);
    setText(els.chip, st.label); els.chip.className = 'chip ' + st.cls;
    setAttr(els.progBtn, 'aria-pressed', d.programming ? 'true' : 'false');
    setCls(els.progBox, 'active', d.programming);
    setCls(els.progBtn.firstChild, 'on', d.programming);
    setText(els.progTxt, d.programming ? 'Programming mode is ON (red indicator lit). ETS can now assign the physical address. Press the button again to switch it off.' : 'Programming mode is off. Press the button, then assign the physical address from ETS.');
    setText(els.addr, d.unassigned ? 'Missing (15.15.255 = not assigned)' : d.address);
    setText(els.app, d.loaded ? 'Loaded' : 'Missing – download the application from ETS');
    setText(els.dl, DOWNLOAD_TEXT[d.downloadState] || d.downloadState);
    setText(els.dlN, d.downloads);
    // parameters
    const sigP = JSON.stringify([d.loaded, d.params]);
    if (sigP !== els.sigParams) { els.sigParams = sigP; renderParams(d); }
    const sigO = JSON.stringify([d.loaded, d.assoc, S.cat[d.product]?.key]);
    if (sigO !== els.sigObjs) { els.sigObjs = sigO; renderObjects(d); }
  }

  function renderParams(d) {
    if (!d.loaded) { els.params.replaceChildren(h('p.hint', { style: 'margin:0' }, 'The parameters appear here after the application has been downloaded from ETS. The values shown are those ETS wrote to the device.')); return; }
    const groups = new Map();
    for (const p of d.params.filter(p => p.visible)) { const g = p.group || 'General'; if (!groups.has(g)) groups.set(g, []); groups.get(g).push(p); }
    els.params.replaceChildren(...[...groups].map(([g, ps]) => {
      const det = h('details.group', { open: openGroups.has(d.name + g) || groups.size === 1 }, h('summary', g), h('dl.kv', ps.flatMap(p => [h('dt', p.text), h('dd', p.value)])));
      det.addEventListener('toggle', () => det.open ? openGroups.add(d.name + g) : openGroups.delete(d.name + g));
      return det;
    }));
  }

  function renderObjects(d) {
    const objs = S.cat[d.product]?.objects || [];
    const byObj = new Map();
    for (const a of d.assoc || []) { if (!byObj.has(a.obj)) byObj.set(a.obj, []); byObj.get(a.obj).push(a.ga); }
    const rows = objs.map(o => h('tr',
      h('td', o.number), h('td', o.text), h('td.mute', o.function), h('td', o.size), h('td', o.dpt || '–'),
      h('td.flags', [...o.flags].map(f => h('span', { title: { R: 'Read', W: 'Write', T: 'Transmit', U: 'Update' }[f] }, f))),
      h('td', d.loaded && byObj.has(o.number)
        ? byObj.get(o.number).sort(naturalCmp).map(ga => h('button.ga-chip', { title: `Show group address ${ga}`, onclick: () => showGroupAddress(ga) }, ga))
        : h('span.faint', d.loaded ? 'none' : '–'))));
    els.objs.replaceChildren(h('table.tbl', h('thead', h('tr', ['#', 'Object', 'Direction', 'Size', 'DPT', 'Flags', 'Group addresses'].map(t => h('th', t)))), h('tbody', rows)),
      h('p.hint', { style: 'margin:.6rem .6rem 0' }, 'Group addresses are those downloaded to the device by ETS (configured). They do not show which telegrams were actually sent.'));
  }

  return { el: root, update };
}
