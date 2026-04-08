# capacitor-iab

WKWebView-based in-app browser plugin for Capacitor. Designed for automating sign-in flows: open a page, let the user log in (or auto-fill credentials), capture form data, and extract page content via JS injection.

iOS only. Capacitor 8+.

## Install

```bash
pnpm add capacitor-iab
npx cap sync
```

## Usage

### Open a browser

```typescript
import { InAppBrowser } from 'capacitor-iab';

await InAppBrowser.open({
  url: 'https://portal.state.gov/login',
  toolbarColor: '#1a1a2e',
  title: 'Sign In',
  closeButtonText: 'Done',
  showNavigationButtons: true,
  showUrlBar: true,
});
```

### Listen for events

```typescript
// Page started loading
InAppBrowser.addListener('loadstart', ({ url }) => {
  console.log('Navigating to:', url);
});

// Page finished loading (DOM ready)
InAppBrowser.addListener('loadstop', ({ url }) => {
  console.log('Loaded:', url);
});

// Navigation error
InAppBrowser.addListener('loaderror', ({ url, code, message }) => {
  console.error(`Error loading ${url}: ${message} (${code})`);
});

// Browser closed
InAppBrowser.addListener('exit', () => {
  console.log('Browser closed');
});

// Message from page JS via postMessage bridge
InAppBrowser.addListener('message', ({ data }) => {
  console.log('Message from page:', data);
});
```

### Extract data from a page

```typescript
InAppBrowser.addListener('loadstop', async () => {
  const { result } = await InAppBrowser.executeScript({
    code: 'document.querySelector(".balance").textContent',
  });
  console.log('Balance:', result);
});
```

### Wait for an element

```typescript
// Wait up to 10s for element to appear in DOM
await InAppBrowser.waitForSelector('.balance', 10000);

const { result } = await InAppBrowser.executeScript({
  code: 'document.querySelector(".balance").textContent',
});
```

### Automate form interaction

`tap()` and `type()` wait for the selector before acting and simulate realistic mobile touch/input events.

```typescript
// Fill a login form
await InAppBrowser.type('#username', 'john@example.com');
await InAppBrowser.type('#password', 's3cret');
await InAppBrowser.tap('#submit');

// Customize timing
await InAppBrowser.type('#username', 'john@example.com', { delay: 80 }); // ms between keystrokes
await InAppBrowser.tap('#submit', { delay: 100 }); // ms between touchstart and touchend
```

`type()` dispatches per-character `beforeinput` and `input` events (mobile keyboard model). `tap()` dispatches `touchstart`, `touchend`, and `click` with coordinates at the element's center.

### Inject persistent scripts

`addUserScript` injects JS that auto-runs on every page load, including navigations. No need to re-inject after each `loadstop`.

```typescript
// Capture credentials on form submit (inject before page JS runs)
await InAppBrowser.addUserScript({
  code: `document.addEventListener('submit', (e) => {
    const form = e.target;
    const username = form.querySelector('#username')?.value;
    const password = form.querySelector('#password')?.value;
    if (username || password) {
      window.webkit.messageHandlers.iab.postMessage(
        JSON.stringify({ type: 'credentials', username, password })
      );
    }
  }, true);`,
  injectionTime: 'atDocumentStart',
});

// Auto-fill saved credentials (inject after DOM is ready)
await InAppBrowser.addUserScript({
  code: `const u = document.querySelector('#username');
    const p = document.querySelector('#password');
    if (u) u.value = 'saved_user';
    if (p) p.value = 'saved_pass';`,
  injectionTime: 'atDocumentEnd',
});

// Clear all injected scripts
await InAppBrowser.removeAllUserScripts();
```

### Inject CSS

```typescript
await InAppBrowser.insertCSS({
  code: 'body { font-size: 18px; } .ads { display: none; }',
});
```

### postMessage bridge

Page JS can send messages to your app:

```javascript
// In the WebView page (via executeScript or addUserScript)
window.webkit.messageHandlers.iab.postMessage({ type: 'data', balance: '$420.00' });
```

```typescript
// In your app
InAppBrowser.addListener('message', ({ data }) => {
  const parsed = JSON.parse(data);
  if (parsed.type === 'credentials') {
    saveToKeychain(parsed.username, parsed.password);
  }
});
```

### Close the browser

```typescript
await InAppBrowser.close();
```

### Full sign-in flow example

```typescript
import { InAppBrowser } from 'capacitor-iab';

async function signIn(portalUrl: string, savedCreds?: { username: string; password: string }) {
  // Listen for credential capture
  InAppBrowser.addListener('message', ({ data }) => {
    const parsed = JSON.parse(data);
    if (parsed.type === 'credentials') {
      saveToKeychain(parsed.username, parsed.password);
    }
  });

  // Open the portal
  await InAppBrowser.open({ url: portalUrl, title: 'Sign In' });

  // Inject submit observer (persists across navigations)
  await InAppBrowser.addUserScript({
    code: `document.addEventListener('submit', (e) => {
      const form = e.target;
      const username = form.querySelector('input[type="text"], input[type="email"]')?.value;
      const password = form.querySelector('input[type="password"]')?.value;
      if (username || password) {
        window.webkit.messageHandlers.iab.postMessage(
          JSON.stringify({ type: 'credentials', username, password })
        );
      }
    }, true);`,
    injectionTime: 'atDocumentStart',
  });

  // Auto-fill if we have saved credentials
  if (savedCreds) {
    await InAppBrowser.addUserScript({
      code: `(() => {
        const u = document.querySelector('input[type="text"], input[type="email"]');
        const p = document.querySelector('input[type="password"]');
        if (u) u.value = '${savedCreds.username}';
        if (p) p.value = '${savedCreds.password}';
      })()`,
      injectionTime: 'atDocumentEnd',
    });
  }

  // Wait for user to land on the dashboard
  InAppBrowser.addListener('loadstop', async ({ url }) => {
    if (url.includes('/dashboard') || url.includes('/account')) {
      await InAppBrowser.waitForSelector('.balance');
      const { result } = await InAppBrowser.executeScript({
        code: 'document.querySelector(".balance").textContent',
      });
      console.log('Balance:', result);
      await InAppBrowser.close();
    }
  });
}
```

## API Reference

<docgen-index>

* [`open(...)`](#open)
* [`close()`](#close)
* [`executeScript(...)`](#executescript)
* [`insertCSS(...)`](#insertcss)
* [`addUserScript(...)`](#adduserscript)
* [`removeAllUserScripts()`](#removealluserscripts)
* [`waitForSelector(...)`](#waitforselector)
* [`tap(...)`](#tap)
* [`type(...)`](#type)
* [`addListener('loadstart', ...)`](#addlistenerloadstart-)
* [`addListener('loadstop', ...)`](#addlistenerloadstop-)
* [`addListener('loaderror', ...)`](#addlistenerloaderror-)
* [`addListener('exit', ...)`](#addlistenerexit-)
* [`addListener('message', ...)`](#addlistenermessage-)
* [`removeAllListeners()`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### open(...)

```typescript
open(options: OpenOptions) => any
```

| Param         | Type                                                |
| ------------- | --------------------------------------------------- |
| **`options`** | <code><a href="#openoptions">OpenOptions</a></code> |

**Returns:** <code>any</code>

--------------------


### close()

```typescript
close() => any
```

**Returns:** <code>any</code>

--------------------


### executeScript(...)

```typescript
executeScript(options: ScriptInjectionOptions) => any
```

| Param         | Type                                                                      |
| ------------- | ------------------------------------------------------------------------- |
| **`options`** | <code><a href="#scriptinjectionoptions">ScriptInjectionOptions</a></code> |

**Returns:** <code>any</code>

--------------------


### insertCSS(...)

```typescript
insertCSS(options: CSSInjectionOptions) => any
```

| Param         | Type                                                                |
| ------------- | ------------------------------------------------------------------- |
| **`options`** | <code><a href="#cssinjectionoptions">CSSInjectionOptions</a></code> |

**Returns:** <code>any</code>

--------------------


### addUserScript(...)

```typescript
addUserScript(options: UserScriptOptions) => any
```

| Param         | Type                                                            |
| ------------- | --------------------------------------------------------------- |
| **`options`** | <code><a href="#userscriptoptions">UserScriptOptions</a></code> |

**Returns:** <code>any</code>

--------------------


### removeAllUserScripts()

```typescript
removeAllUserScripts() => any
```

**Returns:** <code>any</code>

--------------------


### waitForSelector(...)

```typescript
waitForSelector(selector: string, timeout?: number | undefined) => any
```

| Param          | Type                |
| -------------- | ------------------- |
| **`selector`** | <code>string</code> |
| **`timeout`**  | <code>number</code> |

**Returns:** <code>any</code>

--------------------


### tap(...)

```typescript
tap(selector: string, options?: InteractionOptions | undefined) => any
```

| Param          | Type                                                              |
| -------------- | ----------------------------------------------------------------- |
| **`selector`** | <code>string</code>                                               |
| **`options`**  | <code><a href="#interactionoptions">InteractionOptions</a></code> |

**Returns:** <code>any</code>

--------------------


### type(...)

```typescript
type(selector: string, value: string, options?: InteractionOptions | undefined) => any
```

| Param          | Type                                                              |
| -------------- | ----------------------------------------------------------------- |
| **`selector`** | <code>string</code>                                               |
| **`value`**    | <code>string</code>                                               |
| **`options`**  | <code><a href="#interactionoptions">InteractionOptions</a></code> |

**Returns:** <code>any</code>

--------------------


### addListener('loadstart', ...)

```typescript
addListener(eventName: 'loadstart', handler: (event: UrlEvent) => void) => any
```

| Param           | Type                                                              |
| --------------- | ----------------------------------------------------------------- |
| **`eventName`** | <code>'loadstart'</code>                                          |
| **`handler`**   | <code>(event: <a href="#urlevent">UrlEvent</a>) =&gt; void</code> |

**Returns:** <code>any</code>

--------------------


### addListener('loadstop', ...)

```typescript
addListener(eventName: 'loadstop', handler: (event: UrlEvent) => void) => any
```

| Param           | Type                                                              |
| --------------- | ----------------------------------------------------------------- |
| **`eventName`** | <code>'loadstop'</code>                                           |
| **`handler`**   | <code>(event: <a href="#urlevent">UrlEvent</a>) =&gt; void</code> |

**Returns:** <code>any</code>

--------------------


### addListener('loaderror', ...)

```typescript
addListener(eventName: 'loaderror', handler: (event: LoadErrorEvent) => void) => any
```

| Param           | Type                                                                          |
| --------------- | ----------------------------------------------------------------------------- |
| **`eventName`** | <code>'loaderror'</code>                                                      |
| **`handler`**   | <code>(event: <a href="#loaderrorevent">LoadErrorEvent</a>) =&gt; void</code> |

**Returns:** <code>any</code>

--------------------


### addListener('exit', ...)

```typescript
addListener(eventName: 'exit', handler: () => void) => any
```

| Param           | Type                       |
| --------------- | -------------------------- |
| **`eventName`** | <code>'exit'</code>        |
| **`handler`**   | <code>() =&gt; void</code> |

**Returns:** <code>any</code>

--------------------


### addListener('message', ...)

```typescript
addListener(eventName: 'message', handler: (event: MessageEvent) => void) => any
```

| Param           | Type                                                                      |
| --------------- | ------------------------------------------------------------------------- |
| **`eventName`** | <code>'message'</code>                                                    |
| **`handler`**   | <code>(event: <a href="#messageevent">MessageEvent</a>) =&gt; void</code> |

**Returns:** <code>any</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => any
```

**Returns:** <code>any</code>

--------------------


### Interfaces


#### OpenOptions

| Prop                        | Type                                      |
| --------------------------- | ----------------------------------------- |
| **`url`**                   | <code>string</code>                       |
| **`headers`**               | <code>Record&lt;string, string&gt;</code> |
| **`toolbarColor`**          | <code>string</code>                       |
| **`title`**                 | <code>string</code>                       |
| **`closeButtonText`**       | <code>string</code>                       |
| **`showNavigationButtons`** | <code>boolean</code>                      |
| **`showUrlBar`**            | <code>boolean</code>                      |


#### ScriptInjectionOptions

| Prop       | Type                |
| ---------- | ------------------- |
| **`code`** | <code>string</code> |


#### ScriptResult

| Prop         | Type                |
| ------------ | ------------------- |
| **`result`** | <code>string</code> |


#### CSSInjectionOptions

| Prop       | Type                |
| ---------- | ------------------- |
| **`code`** | <code>string</code> |


#### UserScriptOptions

| Prop                   | Type                                                                        |
| ---------------------- | --------------------------------------------------------------------------- |
| **`code`**             | <code>string</code>                                                         |
| **`injectionTime`**    | <code><a href="#userscriptinjectiontime">UserScriptInjectionTime</a></code> |
| **`forMainFrameOnly`** | <code>boolean</code>                                                        |


#### InteractionOptions

| Prop          | Type                |
| ------------- | ------------------- |
| **`timeout`** | <code>number</code> |
| **`delay`**   | <code>number</code> |


#### UrlEvent

| Prop      | Type                |
| --------- | ------------------- |
| **`url`** | <code>string</code> |


#### PluginListenerHandle

| Prop         | Type                      |
| ------------ | ------------------------- |
| **`remove`** | <code>() =&gt; any</code> |


#### LoadErrorEvent

| Prop          | Type                |
| ------------- | ------------------- |
| **`url`**     | <code>string</code> |
| **`code`**    | <code>number</code> |
| **`message`** | <code>string</code> |


#### MessageEvent

| Prop       | Type                 |
| ---------- | -------------------- |
| **`data`** | <code>unknown</code> |


### Type Aliases


#### UserScriptInjectionTime

<code>'atDocumentStart' | 'atDocumentEnd'</code>

</docgen-api>
