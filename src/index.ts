import { registerPlugin } from '@capacitor/core';

import type { InAppBrowserPlugin, InteractionOptions } from './definitions';

const plugin = registerPlugin<InAppBrowserPlugin>('InAppBrowser', {
  web: () => import('./web').then((m) => new m.InAppBrowserWeb()),
});

function escapeJS(str: string): string {
  return str.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

async function waitForSelector(
  selector: string,
  timeout = 5000,
): Promise<void> {
  const s = escapeJS(selector);
  await plugin.executeScript({
    code: `
      await new Promise((resolve, reject) => {
        if (document.querySelector('${s}')) return resolve();
        const t = setTimeout(() => { obs.disconnect(); reject(new Error('waitForSelector timed out: ${s}')); }, ${timeout});
        const obs = new MutationObserver(() => {
          if (document.querySelector('${s}')) { clearTimeout(t); obs.disconnect(); resolve(); }
        });
        obs.observe(document, { childList: true, subtree: true });
      });
      return null;`,
  });
}

async function tap(
  selector: string,
  options?: InteractionOptions,
): Promise<void> {
  const { timeout = 5000, delay = 70 } = options ?? {};
  const s = escapeJS(selector);
  await plugin.executeScript({
    code: `
      await new Promise((resolve, reject) => {
        if (document.querySelector('${s}')) return resolve();
        const t = setTimeout(() => { obs.disconnect(); reject(new Error('waitForSelector timed out: ${s}')); }, ${timeout});
        const obs = new MutationObserver(() => {
          if (document.querySelector('${s}')) { clearTimeout(t); obs.disconnect(); resolve(); }
        });
        obs.observe(document, { childList: true, subtree: true });
      });
      const el = document.querySelector('${s}');
      const rect = el.getBoundingClientRect();
      const x = rect.left + rect.width / 2;
      const y = rect.top + rect.height / 2;
      const touch = new Touch({ identifier: 1, target: el, clientX: x, clientY: y });
      el.dispatchEvent(new TouchEvent('touchstart', { bubbles: true, cancelable: true, touches: [touch], changedTouches: [touch] }));
      await new Promise(r => setTimeout(r, ${delay}));
      el.dispatchEvent(new TouchEvent('touchend', { bubbles: true, cancelable: true, touches: [], changedTouches: [touch] }));
      el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, clientX: x, clientY: y }));
      return null;`,
  });
}

async function type(
  selector: string,
  value: string,
  options?: InteractionOptions,
): Promise<void> {
  const { timeout = 5000, delay = 50 } = options ?? {};
  const s = escapeJS(selector);
  const v = escapeJS(value);
  await plugin.executeScript({
    code: `
      await new Promise((resolve, reject) => {
        if (document.querySelector('${s}')) return resolve();
        const t = setTimeout(() => { obs.disconnect(); reject(new Error('waitForSelector timed out: ${s}')); }, ${timeout});
        const obs = new MutationObserver(() => {
          if (document.querySelector('${s}')) { clearTimeout(t); obs.disconnect(); resolve(); }
        });
        obs.observe(document, { childList: true, subtree: true });
      });
      const el = document.querySelector('${s}');
      el.focus();
      el.value = '';
      for (const ch of '${v}'.split('')) {
        el.dispatchEvent(new InputEvent('beforeinput', { bubbles: true, cancelable: true, inputType: 'insertText', data: ch }));
        el.value += ch;
        el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: ch }));
        await new Promise(r => setTimeout(r, ${delay}));
      }
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return null;`,
  });
}

const InAppBrowser: InAppBrowserPlugin = new Proxy(plugin, {
  get(target, prop, receiver) {
    if (prop === 'waitForSelector') return waitForSelector;
    if (prop === 'tap') return tap;
    if (prop === 'type') return type;
    return Reflect.get(target, prop, receiver);
  },
});

export * from './definitions';
export { InAppBrowser };
