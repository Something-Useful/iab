import type { PluginListenerHandle } from '@capacitor/core';

export interface OpenOptions {
  url: string;
  headers?: Record<string, string>;
  toolbarColor?: string;
  title?: string;
  closeButtonText?: string;
  showNavigationButtons?: boolean;
  showUrlBar?: boolean;
}

export interface ScriptInjectionOptions {
  code: string;
}

export interface CSSInjectionOptions {
  code: string;
}

export interface ScriptResult {
  result: string;
}

export interface UrlEvent {
  url: string;
}

export interface LoadErrorEvent {
  url: string;
  code: number;
  message: string;
}

export interface MessageEvent {
  data: unknown;
}

export interface InteractionOptions {
  timeout?: number;
  delay?: number;
}

export type UserScriptInjectionTime = 'atDocumentStart' | 'atDocumentEnd';

export interface UserScriptOptions {
  code: string;
  injectionTime: UserScriptInjectionTime;
  forMainFrameOnly?: boolean;
}

export interface InAppBrowserPlugin {
  open(options: OpenOptions): Promise<void>;
  close(): Promise<void>;
  executeScript(options: ScriptInjectionOptions): Promise<ScriptResult>;
  insertCSS(options: CSSInjectionOptions): Promise<void>;
  addUserScript(options: UserScriptOptions): Promise<void>;
  removeAllUserScripts(): Promise<void>;
  waitForSelector(selector: string, timeout?: number): Promise<void>;
  tap(selector: string, options?: InteractionOptions): Promise<void>;
  type(selector: string, value: string, options?: InteractionOptions): Promise<void>;

  addListener(
    eventName: 'loadstart',
    handler: (event: UrlEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'loadstop',
    handler: (event: UrlEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'loaderror',
    handler: (event: LoadErrorEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'exit',
    handler: () => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'message',
    handler: (event: MessageEvent) => void,
  ): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}
