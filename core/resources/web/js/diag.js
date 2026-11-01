import { S } from './bus.js';
import { productOf } from './devinfo.js';

/**
 * Diagnostics: everything here is derived from facts the simulator reports (devices, tunnels, the log).
 * Nothing is guessed about ETS itself - the texts say what vKNX can see and what that usually means.
 */

export const interfaceLine = () => S.state?.uplink || S.state?.line || '';
const isCoupler = d => d.product === 'coupler';
const routingOn = () => S.state?.routing !== false;
export const tunnels = () => S.state?.link?.tunnels || [];

/** Lines joined by the couplers that exist: placement <-> main line, for line and area couplers. */
function edges() {
  const E = [];
  for (const d of S.state?.devices || []) {
    if (!isCoupler(d)) continue;
    const v = d.view || {};
    if (v.placement && v.mainLine) E.push([v.placement, v.mainLine, d]);
  }
  return E;
}

/** Set of lines reachable from the interface line through couplers (or everything, if routing is switched off). */
export function reachableFrom(line) {
  const seen = new Set([line]), E = edges();
  for (let grew = true; grew;) {
    grew = false;
    for (const [a, b] of E) {
      if (seen.has(a) && !seen.has(b)) { seen.add(b); grew = true; }
      if (seen.has(b) && !seen.has(a)) { seen.add(a); grew = true; }
    }
  }
  return seen;
}

/** Can a telegram from the interface (ETS) reach the device? Unassigned devices count as being on the interface line. */
export function reachable(d) {
  if (!routingOn() || d.unassigned) return true;
  const iface = interfaceLine(); if (!iface) return true;
  const set = reachableFrom(iface);
  if (isCoupler(d)) { const v = d.view || {}; return !v.placement || set.has(v.placement) || set.has(v.mainLine); }
  return set.has(d.line);
}

export const lineOfAddress = a => { const p = String(a).split('.'); return p.length >= 2 ? p[0] + '.' + p[1] : ''; };

/** The facts behind the short messages: devices that are unreachable, duplicate addresses, tunnels. */
export function problems() {
  const out = [], devs = S.state?.devices || [], iface = interfaceLine(), tun = tunnels();
  const [ia] = iface.split('.');
  for (const d of devs) {
    if (d.unassigned || reachable(d)) continue;
    const dl = isCoupler(d) ? (d.view?.placement || d.line) : d.line;
    out.push({ key: 'unreach-' + d.name, level: 'danger', dev: d.name, title: `${d.name} (${d.address}) cannot reach the interface line ${iface}`,
      text: `ETS enters the bus on line ${iface}, and no coupler connects it to line ${dl}. ETS will report "Device does not respond". Fix it in one of three ways: set the interface to line ${dl} (Settings, KNXnet/IP interface, or the address in ETS under Local Interface Settings), give the device an address on line ${iface}, or add line and area couplers that join ${iface} and ${dl}.` });
  }
  const byAddr = new Map();
  for (const d of devs) if (!d.unassigned) { if (!byAddr.has(d.address)) byAddr.set(d.address, []); byAddr.get(d.address).push(d.name); }
  for (const [a, names] of byAddr) if (names.length > 1)
    out.push({ key: 'dup-' + a, level: 'danger', title: `Address ${a} is used by ${names.length} devices (${names.join(', ')})`,
      text: 'Two devices answer to the same physical address, so ETS reports "address already in use". Remove one of them, or give it a different address from ETS (press its programming button first).' });
  for (const t of tun) { const d = devs.find(x => !x.unassigned && x.address === t.address);
    if (d) out.push({ key: 'tunaddr-' + t.address, level: 'warn', dev: d.name, title: `${d.name} has the same address as an ETS connection (${t.address})`, text: 'Tunnel addresses are line.241 to line.244. Give the device another address.' }); }
  const devsHere = devs.filter(d => !d.unassigned && lineOfAddress(d.address) === iface && /\.24[1-4]$/.test(d.address));
  for (const d of devsHere) out.push({ key: 'tunrange-' + d.name, level: 'warn', dev: d.name, title: `${d.name} sits in the address range of the interface tunnels (${d.address})`, text: 'Addresses 241 to 244 on the interface line are kept for ETS connections. Use a lower device number.' });
  if (tun.length > 1)
    out.push({ key: 'tunnels', level: 'info', title: `${tun.length} ETS / IP connections are open`,
      text: `${tun.map(t => `${t.type} as ${t.address}`).join(', ')}. Every open ETS window or diagnostics tab keeps its own connection. That is fine, but if you see answers twice or unexpected traffic, close the connections you do not need.` });
  void ia;
  return out;
}

const SECURE_BAD = /ignor|discard|invalid|fail|wrong|mismatch|unknown key|not match|reject|denied|error/i;

/** Plain-language hints for what ETS recently tried and what happened, from the device log and the bus telegrams. */
export function hints() {
  const out = [], now = Date.now(), W = 180000, devs = S.state?.devices || [];
  const recent = e => now - e.ts < W && e.seq > (S.monCutoff || 0);
  const seen = new Set();
  const add = h => { if (!seen.has(h.key)) { seen.add(h.key); out.push(h); } };
  for (const e of S.devlog) {
    if (!recent(e)) continue;
    if (e.cat === 'mgmt' && /A_IndividualAddress_Write .* ignored/.test(e.text))
      add({ key: 'prog-' + e.dev, level: 'warn', dev: e.dev, title: `ETS tried to give ${e.dev} an address, but the programming button was not pressed`,
        text: 'Press the programming button on the device (red indicator lit), then assign the address again in ETS.' });
    if (e.cat === 'download' && /ERROR/.test(e.text))
      add({ key: 'dl-' + e.dev, level: 'danger', dev: e.dev, title: `A download step to ${e.dev} failed`, text: e.text.replace(/^ERROR:\s*/, '') + ' – see the device log for the steps before it. Try the download again; if it keeps failing, unload the application in ETS and download again.' });
    if (e.cat === 'secure' && SECURE_BAD.test(e.text) && !/ok|success/i.test(e.text))
      add({ key: 'sec-' + e.dev, level: 'warn', dev: e.dev, title: `The secure layer of ${e.dev} refused something`, text: `"${e.text}". Usual causes: ETS uses another tool key than the one in the device (the device was commissioned in a different ETS project or with another certificate), or a telegram was repeated. Remove the device in ETS and re-add it with its certificate, or reset the vKNX device to the factory state.` });
  }
  if (S.devlog.some(e => recent(e) && e.cat === 'mgmt' && /A_IndividualAddress_Read -> silent/.test(e.text)) && !devs.some(d => d.programming))
    add({ key: 'noprog', level: 'info', title: 'ETS looked for devices in programming mode and none answered', text: 'Press the programming button of the virtual device you want to address, then try again in ETS.' });
  // ETS addressed a device that does not exist, or one that stayed silent
  const lastTo = new Map();
  for (const e of S.telegrams) {
    const tg = e.tg;
    if (!recent(e) || tg.group || tg.origin !== 'ip' || /^T_(Disconnect|ACK|NAK)/.test(tg.svc)) continue;
    lastTo.set(tg.dst, e);
  }
  for (const [dst, e] of lastTo) {
    if (dst === '0.0.0' || tunnels().some(t => t.address === dst)) continue;
    const d = devs.find(x => !x.unassigned && x.address === dst);
    if (!d) { add({ key: 'nodev-' + dst, level: 'warn', title: `ETS is talking to ${dst}, but no vKNX device has that address`, text: 'The address may never have been assigned (press the programming button and assign it again), or the device was removed or got another address. In ETS the device then shows "Device does not respond".' }); continue; }
    const answered = S.telegrams.some(x => x.seq > e.seq && !x.tg.group && x.tg.src === dst);
    if (answered || now - e.ts < 2500 || /^T_Connect$/.test(e.tg.svc)) continue;
    const why = !reachable(d) ? `The device is on a line the interface cannot reach (see the warning about line ${d.line}).`
      : d.secMode ? 'Secure commissioning is on for this device, so it ignores unsecured configuration telegrams. ETS has to use the project tool key: open the project in which the device was commissioned, or re-add the device with its certificate.'
      : !d.loaded && d.downloadState === 'empty' ? 'The device has no application yet. Some ETS services need a loaded application.'
      : 'The request arrived but the device did not answer. Check the device log for the reason.';
    add({ key: 'silent-' + dst, level: 'warn', dev: d.name, title: `${d.name} did not answer ETS`, text: why });
  }
  return out;
}

export const ETS_MESSAGES = [
  ['Device does not respond', 'ETS sent a request and got no answer. Check in this order: 1) does a vKNX device have exactly that address (Installation)? 2) can the interface line reach the device line (warning above)? 3) is secure commissioning on, so the device ignores unsecured requests? 4) did a download just start - wait a few seconds.'],
  ['Address is already in use by another device', 'Something answered when ETS asked for the address. Two vKNX devices with the same address, or a device that was removed in vKNX but is still in the ETS project with its old address. The Installation list shows duplicates.'],
  ['No device in programming mode found', 'Press the programming button on the virtual device (the red indicator lights), then assign the address in ETS. The indicator turns off again by itself after the address is assigned.'],
  ['Download failed / memory write error', 'The device refused a step of the download. The device log shows the last steps and the failing one. A second download usually fixes a download that was interrupted.'],
  ['Secure: could not decrypt / invalid sequence number', 'The tool key in ETS and in the device do not match, or the sequence counter is out of sync. Re-add the device with its certificate, or reset it and commission again.'],
];
