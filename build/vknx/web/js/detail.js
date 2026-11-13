import { h, icon, setText, setCls, setAttr, naturalCmp, ago } from './util.js';
import { api } from './api.js';
import { S, emit, device, showTelegrams, showGroupAddress, selectDevice, hooks } from './bus.js';
import { statusOf, productOf, badgesOf, DOWNLOAD_TEXT } from './devinfo.js';
import { renderGuide, createNextBar } from './guide.js';
import { confirmDialog, menuButton, toast, modal } from './dialogs.js';
import { operate } from './cards.js';
import { paintIcon, forgetIconsOf, loadIcons } from './iconStore.js';
import { openIconEditor } from './iconPicker.js';

const openGroups = new Set();

export async function removeDevice(name) {
  const ok = await confirmDialog({
    title: 'Remove device', danger: true, confirmLabel: 'Remove device',
    message: `Remove "${name}"? Its address, downloaded application and saved state are deleted, and ETS will see the device disappear from the line. Right after the removal you can still undo it.`,
  });
  if (!ok) return;
  if (await operate(`Removing "${name}"`, () => api.remove(name))) {
    // the icons and the place on the floor plan are kept until the undo offer is over
    toast(`"${name}" was removed.`, 'ok', 12000, {
      label: 'Undo',
      onclick: async () => {
        if (await operate(`Putting "${name}" back`, () => api.restore())) { toast(`"${name}" is back, with its address and download.`, 'ok'); await hooks.refresh(); emit('devices'); }
        else forgetIconsOf(name);
      },
      onExpire: () => { if (!(S.state?.devices || []).some(d => d.name === name)) forgetIconsOf(name); },
    });
    if (S.selected === name) selectDevice(null, { drawer: false });
    await hooks.refresh();
    emit('devices');
  }
}

const MAX_NAME = 40;

/** Same rules as the server (DeviceRegistry.checkName); the server has the last word. */
function nameProblem(name, old) {
  if (!name) return 'Give the device a name.';
  if (name !== name.trim()) return 'The name cannot start or end with a space.';
  if (name.length > MAX_NAME) return `The name can be at most ${MAX_NAME} characters.`;
  if (/[\u0000-\u001f\u007f]/.test(name)) return 'The name cannot contain control characters.';
  if (name.toLowerCase() !== old.toLowerCase() && (S.state?.devices || []).some(x => x.name.toLowerCase() === name.toLowerCase())) return `A device called "${name}" already exists.`;
  return '';
}

/** Asks for a new name and renames the device. Address, download, parameters, keys, plan position and icons are kept. */
export function renameDevice(name) {
  const input = h('input.input', { type: 'text', value: name, maxlength: MAX_NAME, 'aria-label': 'New name', style: 'width:100%' });
  const err = h('div.error-text', { role: 'alert' });
  const hint = h('div.hint', { style: 'margin-top:.5rem' }, 'The physical address, the ETS download, the parameters, the position on the floor plan and the icons are kept. Lamps and blinds start from their start state again.');
  let busy = false;
  const dlg = modal({
    title: 'Rename device',
    body: h('div', h('label', { style: 'display:block;margin-bottom:.35rem' }, `New name for "${name}"`), input, err, hint),
    buttons: close => [
      h('button.btn', { onclick: close }, 'Cancel'),
      h('button.btn.primary', { onclick: () => go(close) }, 'Rename')],
  });
  input.addEventListener('input', () => { err.textContent = ''; });
  input.addEventListener('keydown', ev => { if (ev.key === 'Enter') { ev.preventDefault(); go(() => dlg.close()); } });
  input.focus(); input.select();
  async function go(close) {
    if (busy) return;
    const to = input.value;
    if (to === name) { close(); return; }
    const bad = nameProblem(to, name);
    if (bad) { err.textContent = bad; input.focus(); return; }
    busy = true;
    try {
      await api.rename(name, to);
    } catch (e) { busy = false; err.textContent = e.message || 'The device could not be renamed.'; input.focus(); return; }
    close();
    const wasSelected = S.selected === name;
    await loadIcons();
    emit('renamed');
    await hooks.refresh();
    if (wasSelected) selectDevice(to);
    emit('devices');
    toast(`"${name}" is now called "${to}".`, 'ok');
  }
}

export function deviceMenu(name) {
  return menuButton({
    label: `More actions for ${name}`,
    items: () => [
      { label: 'Show details', icon: 'info', onclick: () => { selectDevice(name, { drawer: true }); } },
      { label: 'Show related telegrams', icon: 'devices', onclick: () => showTelegrams(name) },
      { label: 'Rename device…', icon: 'pencil', onclick: () => renameDevice(name) },
      { label: 'Change icons…', icon: 'edit', onclick: () => openIconEditor(name) },
      'hr',
      { label: 'Remove device…', icon: 'trash', danger: true, onclick: () => removeDevice(name) },
    ],
  });
}

const TABS = [['overview', 'Overview'], ['params', 'Parameters'], ['objects', 'Group objects'], ['diag', 'Diagnostics']];

/**
 * Device details. The header (name, status, programming button) and the "Next:" line are always visible; below them the
 * tabs Overview (checklist, status, security), Parameters, Group objects and Diagnostics (last ETS action, line, telegrams).
 */
export function createDetail({ onBack, onClose, empty } = {}) {
  const root = h('div.detail');
  const head = h('div.detail-head');
  const body = h('div');
  const badgeRow = h('div.badge-row'); const next = createNextBar();
  const tabBar = h('div.tabs', { role: 'tablist', 'aria-label': 'Device details' });
  const panes = {};
  root.append(head, badgeRow, next.el, tabBar, body);
  let current = null, tab = 'overview';
  const els = {};

  function showTab(t) {
    tab = t;
    for (const [k, b] of Object.entries(els.tabBtns || {})) { setAttr(b, 'aria-selected', String(k === t)); b.tabIndex = k === t ? 0 : -1; }
    for (const [k, p] of Object.entries(panes)) p.hidden = k !== t;
  }

  function buildFor(name) {
    current = name;
    const d = device(name); if (!d) return;
    const info = productOf(d);
    els.progBtn = h('button.prog-btn.compact', { 'aria-pressed': 'false', title: 'Programming button: press it, then give the device its physical address from ETS', onclick: () => operate('Programming button', () => api.prog(name)) }, h('span.led'), h('span.t', 'Programming button'));
    head.replaceChildren(
      onBack ? h('button.btn.quiet.back-only', { 'aria-label': 'Back to the device list', onclick: onBack }, icon('back'), 'Devices') : null,
      els.ico = h('div.ico', icon(info.icon)),
      h('div.grow', h('h2.ellipsis', d.name), h('div.mute.ellipsis', `${info.title} · ${d.productName}`)),
      h('div.head-actions', els.chip = h('span.chip'), els.progBtn),
      h('button.btn.quiet.icon', { 'aria-label': `Rename ${name}`, title: 'Rename device', onclick: () => renameDevice(name) }, icon('pencil')),
      deviceMenu(name),
      onClose ? h('button.btn.quiet.icon.head-close', { 'aria-label': 'Close details (Esc)', title: 'Close details (Esc)', onclick: onClose }, icon('close')) : null);
    els.progTxt = h('div.hint');
    els.addr = h('span'); els.loc = h('span'); els.app = h('span'); els.dl = h('span'); els.dlN = h('span');
    const check = (label, valueEl) => h('li', h('span.label', label), valueEl);
    els.checks = h('ul.checks', check('Physical address', els.addr), check('Wired to line', h('span', els.loc, ' ', h('button.btn.sm.quiet', { title: 'Show the devices as a topology tree', onclick: () => hooks.showTopology && hooks.showTopology() }, 'Show in topology'))), check('Application', els.app), check('Download', els.dl), check('Downloads received', els.dlN));
    els.params = h('div'); els.objs = h('div.tbl-wrap');
    els.sigParams = ''; els.sigObjs = ''; els.sigSec = '';
    els.guideBox = h('div'); els.guideSig = '';
    els.lastEts = h('div.last-ets');
    els.locIn = h('input.input', { type: 'text', maxlength: 5, placeholder: 'auto', 'aria-label': 'Physical line', style: 'width:6rem' });
    panes.overview = h('div.pane', section('Setup checklist', els.guideBox), section('Status', els.checks), ...(d.secure ? [section('KNX Data Secure', els.sec = h('div'))] : []));
    panes.params = h('div.pane', section('ETS parameters', els.params));
    panes.objects = h('div.pane', section('Communication objects and group addresses', els.objs));
    panes.diag = h('div.pane',
      section('Last ETS action', els.lastEts),
      section('Programming button', els.progTxt),
      section('Physical line', h('div', h('p.hint', { style: 'margin-top:0' }, 'Automatic (empty): the device sits on the line of its address, so it moves when ETS gives it a new address. Enter a line such as 1.2 to wire it by hand instead. Telegrams only cross to other lines through couplers.'),
        els.locIn, h('button.btn', { style: 'margin-left:.5rem', onclick: () => operate('Moving device', () => api.setLocation(name, els.locIn.value.trim())) }, 'Set'))),
      section('Telegrams', h('div', h('p.hint', { style: 'margin-top:0' }, 'Telegrams sent by this device or addressed to one of its group addresses.'),
        h('button.btn', { onclick: () => showTelegrams(name) }, icon('devices'), 'Show related telegrams'))));
    els.tabBtns = {};
    tabBar.replaceChildren(...TABS.map(([k, t]) => els.tabBtns[k] = h('button.tab', { type: 'button', role: 'tab', id: 'tab-' + k, onclick: () => showTab(k),
      onkeydown: ev => { const i = TABS.findIndex(x => x[0] === k), j = ev.key === 'ArrowRight' ? i + 1 : ev.key === 'ArrowLeft' ? i - 1 : -1; if (j >= 0 && j < TABS.length) { ev.preventDefault(); showTab(TABS[j][0]); els.tabBtns[TABS[j][0]].focus(); } } }, t)));
    body.replaceChildren(...Object.values(panes));
    for (const p of Object.values(panes)) p.setAttribute('role', 'tabpanel');
    showTab(tab);
  }
  function section(title, content) { return h('section.section', h('header', h('h3', title)), h('div.body', content)); }

  function update() {
    const d = device(S.selected);
    if (!d) {
      if (current !== null || !body.firstChild) {
        current = null; head.replaceChildren(); badgeRow.replaceChildren(); badgeRow.dataset.sig = ''; next.el.replaceChildren(); next.el.className = 'next-bar'; tabBar.replaceChildren();
        body.replaceChildren(empty ? empty.el : h('div.empty', icon('devices'), 'Select a device to see its details.'));
      }
      if (empty) empty.update();
      return;
    }
    if (current !== d.name) buildFor(d.name);
    paintIcon(els.ico, d.name, productOf(d).icon);
    const st = statusOf(d);
    updateBadges(d);
    const g = renderGuide(d);
    next.update(d, g);
    if (g.sig !== els.guideSig) { els.guideSig = g.sig; els.guideBox.replaceChildren(g.el); }
    setText(els.chip, st.label); els.chip.className = 'chip ' + st.cls;
    setAttr(els.progBtn, 'aria-pressed', d.programming ? 'true' : 'false');
    setCls(els.progBtn.firstChild, 'on', d.programming);
    setText(els.progTxt, d.programming ? 'Programming mode is ON (red indicator lit). ETS can now assign the physical address. Press the button again to switch it off.' : 'Programming mode is off. Press the button, then assign the physical address from ETS.');
    if (d.secure) renderSecure(d);
    setText(els.loc, d.unassigned ? 'not placed (no address yet)' : d.line + (d.location ? ' (set by hand)' : ' (follows the address)'));
    if (document.activeElement !== els.locIn) els.locIn.value = d.location || '';
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

  function updateBadges(d) {
    const B = badgesOf(d), sig = JSON.stringify(B);
    if (badgeRow.dataset.sig !== sig) {
      badgeRow.dataset.sig = sig;
      badgeRow.replaceChildren(...B.map(b => h('span.chip.' + (b.cls || 'plain'), { title: b.title }, icon(b.icon), b.text)));
    }
    const le = d.lastEts ? d.lastEts + ' · ' + ago(d.lastEtsAt) : '';
    if (els.lastEts.dataset.sig !== le) {
      els.lastEts.dataset.sig = le;
      els.lastEts.replaceChildren(icon('list'), h('span', d.lastEts ? [d.lastEts, h('span.mute', ' · ' + ago(d.lastEtsAt))] : h('span.mute', 'none yet – ETS has not changed this device')));
    }
  }

  function renderSecure(d) {
    const sig = [d.serial, d.fdsk, d.cert, d.secMode, d.toolKey].join('|');
    if (els.sigSec === sig) return;
    els.sigSec = sig;
    const copy = (label, text) => h('div', { style: 'display:flex;gap:.5rem;align-items:center;margin:.25rem 0;flex-wrap:wrap' },
      h('span.mute', { style: 'min-width:6.5rem' }, label), h('code', { style: 'user-select:all;word-break:break-all' }, text),
      h('button.btn', { onclick: async () => { try { await navigator.clipboard.writeText(text); toast(label + ' copied.', 'ok'); } catch (e) { toast('Could not copy; select the text instead.', 'warn'); } } }, 'Copy'));
    const groups = d.fdsk.match(/.{4}/g).join(' ');
    els.sec.replaceChildren(
      h('div.lock-box' + (d.secMode && d.toolKey ? '.on' : ''), icon(d.secMode ? 'lock' : 'lockOpen'), h('div',
        h('b', d.secMode && d.toolKey ? 'Secure commissioned' : d.secMode ? 'Security mode on, factory key still in use' : 'Not commissioned securely'),
        h('div.hint', d.secMode ? 'Only ETS with the project key may configure this device now (download, restart, change address). Normal unsecured configuration is ignored, which ETS shows as "Device does not respond".' : 'The device accepts configuration from any ETS project, like a normal device.'),
        h('div.hint', 'Group telegrams: secure commissioning does not encrypt them by itself. A group address is only encrypted (Data Secure) if you mark it secure in ETS and download the group keys. The telegram monitor shows a lock on telegrams that really were encrypted.'))),
      h('p.hint', { style: 'margin-top:0' }, 'ETS needs the device certificate to commission this device securely: in ETS choose Add Device Certificate for this device and type (or paste) the code below. It already contains the serial number and the FDSK. The FDSK is only used for the first secure download; ETS then replaces it with its own key.'),
      copy('Device certificate', d.cert), h('div.hint', { style: 'margin:.1rem 0 .5rem' }, 'ETS entry field: 36 characters in six blocks of six (letters A-Z and digits 2-7; ETS has no 0 or 1). Typing the dashes is not needed.'),
      copy('Serial number', d.serial), copy('FDSK (hex)', d.fdsk), h('div.hint', { style: 'margin:.1rem 0 .5rem' }, 'Serial number and FDSK are shown for reference (ETS shows them after the certificate is accepted): ' + groups),
      h('ul.checks', h('li', h('span.label', 'Security mode'), h('span', d.secMode ? 'On (ETS has commissioned the device)' : 'Off (not commissioned yet)')),
        h('li', h('span.label', 'Key in use'), h('span', d.toolKey ? 'ETS tool key (FDSK replaced)' : 'Factory key (FDSK)'))));
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
    if (!objs.length) { els.objs.replaceChildren(h('p.hint', { style: 'margin:.6rem' }, 'This product has no group objects. It is configured with parameters only.')); return; }
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
