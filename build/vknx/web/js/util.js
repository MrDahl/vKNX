import { ic } from './icons.js';
export { ic };

// replaceChildren() with null/false/undefined/arrays tolerated (the native one would print "null").
const nativeReplace = Element.prototype.replaceChildren;
Element.prototype.replaceChildren = function (...kids) {
  return nativeReplace.apply(this, kids.flat(Infinity).filter(k => k !== null && k !== undefined && k !== false));
};

export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

/** Tiny element builder: h('div.card#id', {onclick, 'aria-label': 'x', dataset:{a:1}}, child, 'text', [more]) */
export function h(spec, attrs, ...kids) {
  const m = /^([a-z0-9]+)?((?:[.#][\w-]+)*)$/i.exec(spec);
  const el = document.createElement(m[1] || 'div');
  for (const part of (m[2].match(/[.#][\w-]+/g) || [])) {
    if (part[0] === '.') el.classList.add(part.slice(1)); else el.id = part.slice(1);
  }
  if (attrs && (attrs.nodeType || typeof attrs === 'string' || Array.isArray(attrs))) { kids.unshift(attrs); attrs = null; }
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else if (k === 'html') el.innerHTML = v;
    else if (k === 'class') v.split(' ').filter(Boolean).forEach(c => el.classList.add(c));
    else if (k in el && k !== 'list' && typeof v !== 'object' && k !== 'type' && k !== 'role') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  append(el, kids);
  return el;
}
function append(el, kids) {
  for (const k of kids.flat(Infinity)) {
    if (k === null || k === undefined || k === false) continue;
    el.append(k.nodeType ? k : document.createTextNode(String(k)));
  }
}
export const icon = (name, cls) => { const t = document.createElement('template'); t.innerHTML = ic(name, cls); return t.content.firstChild; };

export function esc(s) { return String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }

/** Writers that skip no-op writes, so live updates never touch the DOM unnecessarily. */
export function setText(el, v) { v = String(v); if (el.textContent !== v) el.textContent = v; }
export function setAttr(el, k, v) { if (v === null || v === undefined || v === false) { if (el.hasAttribute(k)) el.removeAttribute(k); } else { v = v === true ? '' : String(v); if (el.getAttribute(k) !== v) el.setAttribute(k, v); } }
export function setCls(el, c, on) { if (el.classList.contains(c) !== !!on) el.classList.toggle(c, !!on); }
export function setStyle(el, k, v) { if (el.style.getPropertyValue(k) !== String(v)) el.style.setProperty(k, v); }

/**
 * Keeps the children of `parent` in sync with `items` (stable order, elements reused by key). Elements that are
 * already in the right place are not touched, so focus, scroll position and pointer capture survive updates.
 */
export function syncKeyed(parent, items, keyOf, create, update) {
  const existing = new Map();
  for (const c of parent.children) if (c.dataset.key !== undefined) existing.set(c.dataset.key, c);
  const seen = new Set();
  let prev = null;
  for (const it of items) {
    const k = String(keyOf(it));
    let el = existing.get(k);
    if (!el) { el = create(it); el.dataset.key = k; }
    update(el, it);
    const want = prev ? prev.nextElementSibling : parent.firstElementChild;
    if (el !== want) parent.insertBefore(el, want);
    prev = el; seen.add(k);
  }
  for (const [k, el] of existing) if (!seen.has(k)) el.remove();
}

export const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
export const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
export const pad = n => String(n).padStart(2, '0');
export function clock(ms) { const d = new Date(ms); return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`; }
export function ago(ms) { const s = Math.max(0, Math.round((Date.now() - ms) / 1000)); return s < 60 ? `${s} s ago` : s < 3600 ? `${Math.floor(s / 60)} min ago` : `${Math.floor(s / 3600)} h ago`; }
export const naturalCmp = (a, b) => String(a).localeCompare(String(b), undefined, { numeric: true });

/** Group object flags as ETS shows them: C R W T U (bit values from the group object table). */
export function flagLetters(f) { return [[4, 'C'], [8, 'R'], [16, 'W'], [64, 'T'], [128, 'U']].filter(x => f & x[0]).map(x => x[1]).join(''); }

export function pref(key, def) { try { const v = localStorage.getItem('vknx-' + key); return v === null ? def : JSON.parse(v); } catch (e) { return def; } }
export function setPref(key, v) { try { localStorage.setItem('vknx-' + key, JSON.stringify(v)); } catch (e) { /* storage may be unavailable */ } }
