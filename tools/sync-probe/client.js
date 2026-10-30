'use strict';
/**
 * Minimal Firebase Realtime Database REST client, streaming included.
 *
 * The push channel is the database's own server-sent event stream (`Accept: text/event-stream`),
 * which is the same fan-out the Firebase Android SDK and the Blogger page's `onChildChanged` ride on.
 * Nothing here polls: a probe that polled would measure its own timer, not the backend.
 *
 * The id token arrives as an argument and is only ever interpolated into a request. It is never
 * logged, never written to a file, and never part of a returned object.
 */

const DEFAULT_BASE = 'https://vault-35423-default-rtdb.asia-southeast1.firebasedatabase.app';

class RtdbClient {
  constructor({ token, base = DEFAULT_BASE, fetchImpl = globalThis.fetch } = {}) {
    if (typeof token !== 'string' || token.trim() === '') {
      throw new Error('An Firebase id token is required; the database rules reject every anonymous request.');
    }
    if (typeof fetchImpl !== 'function') throw new Error('This Node build has no fetch()');
    this.token = token.trim();
    this.base = base.replace(/\/+$/, '');
    this.fetch = fetchImpl;
  }

  url(path, query = {}) {
    const q = new URLSearchParams({ auth: this.token, ...query });
    return `${this.base}${normalize(path)}.json?${q.toString()}`;
  }

  async get(path) {
    const res = await this.fetch(this.url(path), { headers: { Accept: 'application/json' } });
    if (!res.ok) throw new Error(`GET ${path} -> ${res.status} ${await brief(res)}`);
    return res.json();
  }

  async put(path, value) {
    const res = await this.fetch(this.url(path), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(value)
    });
    if (!res.ok) throw new Error(`PUT ${path} -> ${res.status} ${await brief(res)}`);
    return res.json();
  }

  /** Server-side clock: `_value` is the timestamp the database would stamp. */
  async serverClock(path) {
    const res = await this.fetch(this.url(path, { 'shallow': 'true' }), { method: 'PUT', body: 'null' });
    if (!res.ok) throw new Error(`clock ${path} -> ${res.status} ${await brief(res)}`);
    return res.json();
  }

  async remove(path) {
    const res = await this.fetch(this.url(path), { method: 'DELETE' });
    if (!res.ok) throw new Error(`DELETE ${path} -> ${res.status} ${await brief(res)}`);
    return true;
  }

  /**
   * Opens the change stream for a path and calls `onEvent({type, path, data})` for every frame.
   * Resolves when the caller aborts; rejects on a real HTTP failure. Keep-alives are dropped.
   */
  openStream(path, { onEvent, signal }) {
    return (async () => {
      const res = await this.fetch(this.url(path, { includeValueChanged: 'true' }), {
        headers: { Accept: 'text/event-stream' },
        signal
      });
      if (!res.ok) throw new Error(`stream ${path} -> ${res.status} ${await brief(res)}`);
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let pendingType = 'put';
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let line;
        const cut = buffer.lastIndexOf('\n');
        if (cut === -1) continue;
        line = buffer.slice(0, cut);
        buffer = buffer.slice(cut + 1);
        for (const raw of line.split('\n')) {
          const text = raw.replace(/\r$/, '');
          if (text.startsWith('event:')) {
            pendingType = text.slice(6).trim();
          } else if (text.startsWith('data:')) {
            const body = text.slice(5).trim();
            if (pendingType === 'keep-alive' || body === '') continue;
            let frame = null;
            try {
              frame = JSON.parse(body);
            } catch {
              continue;
            }
            onEvent({ type: pendingType, path: frame.path ?? '/', data: frame.data ?? null });
          }
        }
      }
      return true;
    })();
  }
}

function normalize(path) {
  const clean = String(path).replace(/^\/+|\/+$/g, '');
  return clean === '' ? '' : `/${clean}`;
}

async function brief(res) {
  try {
    return (await res.text()).slice(0, 160).replace(/\s+/g, ' ');
  } catch {
    return '(no body)';
  }
}

module.exports = { RtdbClient, DEFAULT_BASE, normalize };
