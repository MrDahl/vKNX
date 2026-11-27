import { S } from './bus.js';
import { gaIndex } from './devinfo.js';

/** Names for addresses: devices for physical addresses, "Device: object" for group addresses (from what ETS downloaded). */
export function nameOfAddr(addr) {
  if (S.state?.link?.tunnels?.some(t => t.address === addr)) return 'ETS interface';
  return S.state?.devices.find(d => !d.unassigned && d.address === addr)?.name || '';
}
export function nameOfGa(ga) {
  const m = gaIndex().get(ga) || [];
  if (!m.length) return '';
  return `${m[0].dev}: ${m[0].text}` + (m.length > 1 ? ` +${m.length - 1}` : '');
}

/**
 * Data Secure / secure management telegrams carry APCI 0x3F1 and a security control field (SCF) byte.
 * Only what is visible without the key is read here: the type and the sequence number.
 */
export function secureInfo(tg) {
  const b = tg.tpdu.match(/../g)?.map(x => parseInt(x, 16)) || [];
  if (b.length < 9 || (b[0] & 3) !== 3 || b[1] !== 0xF1) return null;
  const scf = b[2], svc = scf & 7;
  return { tool: !!(scf & 0x80), service: svc === 0 ? 'data' : svc === 2 ? 'syncReq' : svc === 3 ? 'syncRes' : 'other',
    seq: b.slice(3, 9).reduce((a, x) => a * 256 + x, 0) };
}

export function secureLabel(s) {
  const who = s.tool ? 'tool key' : 'group key';
  return s.service === 'data' ? `Secure data (${who})` : s.service === 'syncReq' ? 'Secure sync request' : s.service === 'syncRes' ? 'Secure sync response' : 'Secure service';
}

/** A vKNX device that logged "-> object" for this secure group telegram: evidence that it was decrypted and accepted. */
export function acceptedBy(e) {
  const tg = e.tg; if (!tg.group) return null;
  const hits = S.devlog.filter(x => x.cat === 'rx' && Math.abs(x.ts - e.ts) < 1500 && x.text.includes(`-> ${tg.dst} [`) && x.text.includes('-> object'));
  if (!hits.length) return null;
  const data = hits[0].text.match(/\[([0-9a-f]*)\]/)?.[1] || '';
  return { devs: [...new Set(hits.map(x => x.dev))], data };
}

const has = (m, bit) => (m.flags & bit) !== 0;

/** "Explain this telegram": a few plain sentences, built only from the telegram and from what ETS downloaded. */
export function explain(e) {
  const tg = e.tg, out = [], sn = nameOfAddr(tg.src) || tg.src, sec = secureInfo(tg);
  const members = tg.group ? (gaIndex().get(tg.dst) || []) : [];
  const names = list => [...new Set(list.map(m => m.dev))].join(', ');
  const origin = tg.origin === 'ip' ? 'It came in through the KNXnet/IP interface (ETS or another IP program).' : 'A virtual device sent it onto the bus.';
  if (sec) {
    if (sec.service === 'data') {
      out.push(`${sn} sent an encrypted telegram (KNX Data Secure) to ${tg.group ? 'group address ' + tg.dst : tg.dst}. Sequence number ${sec.seq}: it counts up with every secure telegram, so a recorded telegram cannot be replayed.`);
      out.push(sec.tool ? 'It is protected with the device\'s tool key: this is ETS configuring a secure device. Only someone with the project key can do that.' : 'It is protected with the group key of that group address. Only devices that were given the key by ETS can read it.');
      const acc = acceptedBy(e);
      if (acc) out.push(`${acc.devs.join(', ')} accepted it and read the value${acc.data ? ' 0x' + acc.data : ''}. That shows the key and sequence number were correct.`);
      else if (tg.group) out.push(members.length ? `${names(members)} ${members.length === 1 ? 'has' : 'have'} this address, but no vKNX device has accepted it (yet). If a lamp should have reacted: the group key is missing or different, or the sequence number was too old.` : 'No vKNX device has this address, so nobody can read it.');
    } else if (sec.service === 'syncReq') out.push(`${sn} asks the receiver for its current secure sequence number (S-A_Sync request). Both sides need matching counters; this is the first step of a secure conversation.`);
    else if (sec.service === 'syncRes') out.push(`${sn} answers a secure sync request with its sequence number. After this the two sides can exchange secure telegrams.`);
    else out.push('A secure-layer telegram (KNX Secure).');
    out.push(origin);
    return out;
  }
  if (tg.group && /GroupValueWrite/.test(tg.svc)) {
    out.push(`${sn} tells group address ${tg.dst}${nameOfGa(tg.dst) ? ' (' + nameOfGa(tg.dst) + ')' : ''} a new value: ${tg.value || ('raw ' + (tg.tpdu.slice(4) || 'bits in the header'))}.`);
    const lis = members.filter(m => has(m, 16));
    out.push(lis.length ? `Devices that have this address on an object with the Write flag take the value: ${lis.map(m => `${m.dev} (${m.text})`).join(', ')}. Whether they really did is shown in the device log.` : 'No device has this address on an object that accepts writes, so nothing reacts.');
    if (!members.some(m => has(m, 64) && m.dev === nameOfAddr(tg.src))) { /* sender not identifiable by flags: say nothing */ }
  } else if (tg.group && /GroupValueRead/.test(tg.svc)) {
    out.push(`${sn} asks everyone on group address ${tg.dst} for its current value.`);
    const ans = members.filter(m => has(m, 8));
    out.push(ans.length ? `Objects with the Read flag answer with a "Group value response": ${ans.map(m => `${m.dev} (${m.text})`).join(', ')}.` : 'No object on this address has the Read flag, so nobody will answer.');
  } else if (tg.group && /GroupValueResponse/.test(tg.svc)) {
    out.push(`${sn} answers a read request for ${tg.dst} with its value: ${tg.value || 'see raw data'}. Objects with the Update flag take it over.`);
  } else if (/^T_Connect/.test(tg.svc)) out.push(`${sn} opens a point-to-point connection to ${tg.dst}${nameOfAddr(tg.dst) ? ' (' + nameOfAddr(tg.dst) + ')' : ''}. ETS does this before it configures a device.`);
  else if (/^T_Disconnect/.test(tg.svc)) out.push(`The point-to-point connection between ${tg.src} and ${tg.dst} is closed.`);
  else if (/^T_ACK/.test(tg.svc)) out.push(`${sn} confirms that it received the previous telegram.`);
  else if (/IndividualAddress.*Write/i.test(tg.svc)) out.push('ETS assigns a physical address to the device whose programming button is on.');
  else if (/IndividualAddress.*Read/i.test(tg.svc)) out.push('ETS asks which device is in programming mode (to find the one you pressed).');
  else if (/IndividualAddress.*Response/i.test(tg.svc)) out.push(`${sn} says it is in programming mode and tells its address.`);
  else if (/Memory.*Write/i.test(tg.svc)) out.push('ETS writes bytes into the device memory. This is the real data of a download: tables and parameters.');
  else if (/Memory.*Read/i.test(tg.svc)) out.push('ETS reads bytes from the device memory, for example to check what is already loaded.');
  else if (/Property.*Write/i.test(tg.svc)) out.push('ETS sets a property of the device (load control, security settings, table references).');
  else if (/Property.*Read|Property.*Response/i.test(tg.svc)) out.push('A device property is read, or the answer to such a request.');
  else if (/DeviceDescriptor/i.test(tg.svc)) out.push('ETS asks what kind of device it is talking to (the mask version). The answer decides which services ETS uses.');
  else if (/Restart/i.test(tg.svc)) out.push('ETS restarts the device. After a download this starts the new application.');
  else if (/Authorize/i.test(tg.svc)) out.push('ETS authorises itself with the device (access level).');
  else out.push(`Service: ${tg.svc}. It is shown as it appears on the bus; there is no short explanation for it yet.`);
  out.push(origin + ` Priority ${tg.prio}, hop count ${tg.hops}.`);
  return out;
}
