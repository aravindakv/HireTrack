// Access token: memory only (never localStorage), so injected scripts can't read it from storage.
let accessToken = null;
let refreshing = null;
const listeners = new Set();

export class ApiError extends Error {
  constructor(status, code, message, retryAfter) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }
}

export const newIdempotencyKey = () => crypto.randomUUID();

export function onSessionChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function setToken(token) {
  accessToken = token;
  listeners.forEach((fn) => fn(token));
}

async function toError(res) {
  let code = 'HTTP_' + res.status;
  let message = 'Something went wrong. Try again.';
  try {
    const body = await res.json();
    if (body?.error) ({ code, message } = body.error);
  } catch { /* not JSON */ }
  return new ApiError(res.status, code, message, Number(res.headers.get('Retry-After')) || undefined);
}

/** One refresh at a time, shared by every request that got a 401. */
export function refreshSession() {
  if (!refreshing) {
    refreshing = fetch('/api/v1/auth/refresh', { method: 'POST', credentials: 'include' })
      .then(async (res) => {
        if (!res.ok) {
          setToken(null);
          throw await toError(res);
        }
        const body = await res.json();
        setToken(body.accessToken);
        return body;
      })
      .finally(() => { refreshing = null; });
  }
  return refreshing;
}

export function setSession(authResponse) {
  setToken(authResponse?.accessToken ?? null);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * @param path            e.g. '/applications'
 * @param idempotencyKey  pass the SAME key for every retry of one user action
 * @param ifMatch         ETag for optimistic locking
 * @param timeoutMs       20 s for matches (timeout chain, Phase 6), 8 s otherwise
 */
export async function api(path, { method = 'GET', body, form, idempotencyKey, ifMatch, timeoutMs = 8000, auth = true } = {}) {
  for (let attempt = 0; attempt < 10; attempt++) {
    const headers = {};
    if (auth && accessToken) headers.Authorization = `Bearer ${accessToken}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
    if (ifMatch) headers['If-Match'] = ifMatch;

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    let res;
    try {
      res = await fetch('/api/v1' + path, {
        method, headers, credentials: 'include', signal: controller.signal,
        body: form ?? (body !== undefined ? JSON.stringify(body) : undefined),
      });
    } catch (e) {
      clearTimeout(timer);
      // Network error or timeout: retrying is safe only with an idempotency key (or a GET).
      if ((idempotencyKey || method === 'GET') && attempt < 1) { await sleep(500); continue; }
      throw new ApiError(0, 'NETWORK', 'Can\'t reach HireTrack. Check your connection and try again.');
    }
    clearTimeout(timer);

    if (res.status === 401 && auth && attempt === 0) {
      try { await refreshSession(); continue; } catch { throw await toError(res); }
    }
    if (res.status === 409 && idempotencyKey) {
      const err = await toError(res);
      if (err.code === 'REQUEST_IN_PROGRESS') { await sleep((err.retryAfter || 2) * 1000); continue; }
      throw err;
    }
    if (!res.ok) throw await toError(res);
    const data = res.status === 204 ? null : await res.json();
    return { data, etag: res.headers.get('ETag'), status: res.status };
  }
  throw new ApiError(0, 'GAVE_UP', 'This is taking longer than expected. Try again.');
}