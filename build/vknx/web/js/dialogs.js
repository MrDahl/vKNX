import { h, icon } from './util.js';

const toasts = h('div.toasts', { 'aria-live': 'polite', role: 'status' });
document.body.append(toasts);

/** @param action optional { label, onclick, onExpire }: a button in the toast (for example Undo); onExpire runs when the toast goes away unused */
export function toast(message, kind = 'info', ms = 5000, action = null) {
  let used = false, gone = false;
  const end = () => { if (gone) return; gone = true; t.remove(); if (action && !used) action.onExpire?.(); };
  const t = h(`div.toast.${kind}`, icon(kind === 'error' ? 'alert' : kind === 'ok' ? 'check' : 'info'), h('span', message),
    action ? h('button.toast-act', { type: 'button', onclick: () => { used = true; end(); action.onclick(); } }, action.label) : null,
    h('button', { 'aria-label': 'Dismiss', onclick: end }, icon('close')));
  toasts.append(t);
  setTimeout(end, kind === 'error' ? 12000 : ms);
}

/** Opens a modal dialog built from body nodes; resolves when closed. Returns the <dialog> for control. */
export function modal({ title, body, buttons, wide, cls }) {
  const dlg = h('dialog', { 'aria-labelledby': 'dlg-title' });
  if (cls) dlg.classList.add(cls);
  else if (wide) dlg.style.maxWidth = 'min(94vw, 860px)';
  const close = () => dlg.close();
  dlg.append(
    h('div.dlg-head', h('h2#dlg-title', title), h('button.btn.quiet.icon', { 'aria-label': 'Close', title: 'Close', onclick: close }, icon('close'))),
    h('div.dlg-body', body),
    buttons ? h('div.dlg-foot', buttons(close)) : null);
  dlg.addEventListener('close', () => dlg.remove());
  document.body.append(dlg);
  dlg.showModal();
  return dlg;
}

/** Confirmation dialog; resolves true when confirmed. */
export function confirmDialog({ title, message, confirmLabel, danger }) {
  return new Promise(resolve => {
    let result = false;
    const dlg = modal({
      title, body: h('div', { style: 'margin:0' }, message),
      buttons: close => [
        h('button.btn', { onclick: close }, 'Cancel'),
        h(`button.btn.${danger ? 'danger' : 'primary'}`, { onclick: () => { result = true; close(); } }, confirmLabel)],
    });
    dlg.addEventListener('close', () => resolve(result));
    dlg.querySelector('.dlg-foot .btn').focus();
  });
}

/** Small popup menu anchored to a button. items: [{label, icon, onclick, danger}] or 'hr'. */
export function menuButton({ label, items, text }) {
  const wrap = h('div.menu-wrap');
  const btn = text ? h('button.btn', { title: label, 'aria-haspopup': 'menu', 'aria-expanded': 'false' }, icon('cog'), text)
    : h('button.btn.quiet.icon', { 'aria-label': label, title: label, 'aria-haspopup': 'menu', 'aria-expanded': 'false' }, icon('more'));
  let menu = null;
  const close = () => { if (menu) { menu.remove(); menu = null; btn.setAttribute('aria-expanded', 'false'); document.removeEventListener('pointerdown', outside, true); } };
  const outside = e => { if (menu && !wrap.contains(e.target)) close(); };
  btn.addEventListener('click', () => {
    if (menu) { close(); return; }
    menu = h('div.menu', { role: 'menu' }, items().map(it => it === 'hr' ? h('hr') :
      h(`button${it.danger ? '.danger' : ''}`, { role: 'menuitem', onclick: () => { close(); it.onclick(); } }, it.icon ? icon(it.icon) : null, it.label)));
    wrap.append(menu);
    btn.setAttribute('aria-expanded', 'true');
    document.addEventListener('pointerdown', outside, true);
    menu.querySelector('button')?.focus();
  });
  wrap.addEventListener('keydown', e => { if (e.key === 'Escape') { close(); btn.focus(); } });
  wrap.append(btn);
  return wrap;
}
