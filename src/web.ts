import { WebPlugin } from '@capacitor/core';

import type {
  CSSInjectionOptions,
  InAppBrowserPlugin,
  OpenOptions,
  ScriptInjectionOptions,
  ScriptResult,
  InteractionOptions,
  UserScriptOptions,
} from './definitions';

export class InAppBrowserWeb extends WebPlugin implements InAppBrowserPlugin {
  async open(options: OpenOptions): Promise<void> {
    window.open(options.url, '_blank');
  }

  async close(): Promise<void> {
    throw this.unimplemented('close is not supported on web');
  }

  async executeScript(_options: ScriptInjectionOptions): Promise<ScriptResult> {
    throw this.unimplemented('executeScript is not supported on web');
  }

  async insertCSS(_options: CSSInjectionOptions): Promise<void> {
    throw this.unimplemented('insertCSS is not supported on web');
  }

  async addUserScript(_options: UserScriptOptions): Promise<void> {
    throw this.unimplemented('addUserScript is not supported on web');
  }

  async removeAllUserScripts(): Promise<void> {
    throw this.unimplemented('removeAllUserScripts is not supported on web');
  }

  async waitForSelector(_selector: string, _timeout?: number): Promise<void> {
    throw this.unimplemented('waitForSelector is not supported on web');
  }

  async tap(_selector: string, _options?: InteractionOptions): Promise<void> {
    throw this.unimplemented('tap is not supported on web');
  }

  async type(
    _selector: string,
    _value: string,
    _options?: InteractionOptions,
  ): Promise<void> {
    throw this.unimplemented('type is not supported on web');
  }
}
