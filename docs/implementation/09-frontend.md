# Phase 9 — Frontend (React + Vite)

**Goal:** the single-page app from the spec: signup/login, resumes, AI match, Kanban pipeline with interviews, dashboard and Job Feed. Its API client follows the platform contracts:

- the access token lives **in memory** and refreshes silently from the `HttpOnly` cookie
- every create action sends an `Idempotency-Key`, generated once per user action and reused on retries
- stage changes send `If-Match`, and a `412` shows a "changed in another tab" message
- the server's error `message` is shown as is
- no user or AI text is ever rendered as HTML

**Time:** 3 days.

**Runs with:** the gateway on `localhost:8080` plus whichever services the page uses. In development, Vite proxies `/api` to the gateway, so the browser sees one origin and the refresh cookie works.

---

## Step 9.1 — Project setup

```bash
cd frontend
npm create vite@5.5.2 . -- --template react
npm install react-router-dom@6.28.0 @tanstack/react-query@5.59.16
npm install
```

`frontend/package.json`. Check that the scripts and versions match:

```json
{
  "name": "hiretrack-frontend",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview --port 5173"
  },
  "dependencies": {
    "@tanstack/react-query": "5.59.16",
    "react": "18.3.1",
    "react-dom": "18.3.1",
    "react-router-dom": "6.28.0"
  },
  "devDependencies": {
    "@vitejs/plugin-react": "4.3.3",
    "vite": "5.4.10"
  }
}
```

`frontend/vite.config.js`:

```js
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Same-origin in development: the refresh cookie (Path=/api/v1/auth) just works.
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
});
```

Delete the template's `src/App.css`, `src/assets/` and the contents of `src/index.css`. You replace them below.

**Verify**

```bash
npm run dev
```

Open `http://localhost:5173`. The Vite starter page loads.

## Step 9.2 — API client

`src/api/client.js`:

```js
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
```

## Step 9.3 — Auth context

`src/auth/AuthContext.jsx`:

```jsx
import { createContext, useContext, useEffect, useState } from 'react';
import { api, onSessionChange, refreshSession, setSession } from '../api/client.js';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    // Restore the session from the refresh cookie on page load.
    refreshSession().then((s) => setUser(s.user)).catch(() => setUser(null)).finally(() => setReady(true));
    return onSessionChange((token) => { if (!token) setUser(null); });
  }, []);

  async function login(email, password) {
    const { data } = await api('/auth/login', { method: 'POST', body: { email, password }, auth: false });
    setSession(data);
    setUser(data.user);
  }

  async function signup(name, email, password) {
    const { data } = await api('/auth/signup', { method: 'POST', body: { name, email, password }, auth: false });
    setSession(data);
    setUser(data.user);
  }

  async function logout() {
    try { await api('/auth/logout', { method: 'POST', auth: false }); } finally {
      setSession(null);
      setUser(null);
    }
  }

  return <AuthContext.Provider value={{ user, ready, login, signup, logout }}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
```

## Step 9.4 — App shell and routing

`src/main.jsx`:

```jsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from './auth/AuthContext.jsx';
import App from './App.jsx';
import './index.css';

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: true, staleTime: 10_000 } },
});

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
);
```

`src/App.jsx`:

```jsx
import { Navigate, NavLink, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext.jsx';
import AuthPage from './pages/AuthPage.jsx';
import ResumePage from './pages/ResumePage.jsx';
import MatchPage from './pages/MatchPage.jsx';
import BoardPage from './pages/BoardPage.jsx';
import DashboardPage from './pages/DashboardPage.jsx';
import JobFeedPage from './pages/JobFeedPage.jsx';

function Protected({ children }) {
  const { user, ready } = useAuth();
  if (!ready) return <p className="muted pad">Loading…</p>;
  return user ? children : <Navigate to="/login" replace />;
}

export default function App() {
  const { user, logout } = useAuth();
  return (
    <div className="app">
      <header className="topbar">
        <strong className="brand">HireTrack AI</strong>
        {user && (
          <nav>
            <NavLink to="/dashboard">Dashboard</NavLink>
            <NavLink to="/board">Pipeline</NavLink>
            <NavLink to="/match">Match</NavLink>
            <NavLink to="/jobs">Job Feed</NavLink>
            <NavLink to="/resume">Resume</NavLink>
            <button className="link" onClick={logout}>Log out</button>
          </nav>
        )}
      </header>
      <main className="content">
        <Routes>
          <Route path="/login" element={<AuthPage mode="login" />} />
          <Route path="/signup" element={<AuthPage mode="signup" />} />
          <Route path="/dashboard" element={<Protected><DashboardPage /></Protected>} />
          <Route path="/board" element={<Protected><BoardPage /></Protected>} />
          <Route path="/match" element={<Protected><MatchPage /></Protected>} />
          <Route path="/jobs" element={<Protected><JobFeedPage /></Protected>} />
          <Route path="/resume" element={<Protected><ResumePage /></Protected>} />
          <Route path="*" element={<Navigate to={user ? '/dashboard' : '/login'} replace />} />
        </Routes>
      </main>
    </div>
  );
}
```

`src/components/ErrorBanner.jsx`:

```jsx
export default function ErrorBanner({ error }) {
  if (!error) return null;
  return <div className="banner error" role="alert">{error.message}</div>;
}
```

## Step 9.5 — Pages

`src/pages/AuthPage.jsx`:

```jsx
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import ErrorBanner from '../components/ErrorBanner.jsx';

export default function AuthPage({ mode }) {
  const { login, signup } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState({ name: '', email: '', password: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const isSignup = mode === 'signup';

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (isSignup) await signup(form.name, form.email, form.password);
      else await login(form.email, form.password);
      navigate('/dashboard');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });
  return (
    <form className="card narrow" onSubmit={submit}>
      <h1>{isSignup ? 'Create your account' : 'Log in'}</h1>
      <ErrorBanner error={error} />
      {isSignup && <label>Name<input id="name" value={form.name} onChange={set('name')} required maxLength={100} /></label>}
      <label>Email<input id="email" type="email" value={form.email} onChange={set('email')} required /></label>
      <label>Password<input id="password" type="password" value={form.password} onChange={set('password')} required minLength={8} /></label>
      {isSignup && (
        <p className="muted small">
          Your resume and the job descriptions you paste are sent to our AI provider to calculate matches.
          Your job search keywords and location are sent to Adzuna.
        </p>
      )}
      <button disabled={busy}>{busy ? 'Please wait…' : isSignup ? 'Sign up' : 'Log in'}</button>
      <p className="muted">
        {isSignup ? <>Have an account? <Link to="/login">Log in</Link></> : <>New here? <Link to="/signup">Sign up</Link></>}
      </p>
    </form>
  );
}
```

`src/pages/ResumePage.jsx`:

```jsx
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, newIdempotencyKey } from '../api/client.js';
import ErrorBanner from '../components/ErrorBanner.jsx';

export default function ResumePage() {
  const qc = useQueryClient();
  const resumes = useQuery({ queryKey: ['resumes'], queryFn: () => api('/resumes').then((r) => r.data) });
  const [text, setText] = useState('');
  const [file, setFile] = useState(null);
  const [key, setKey] = useState(newIdempotencyKey);     // one key per upload attempt, reused on retry

  const upload = useMutation({
    mutationFn: () => {
      if (file) {
        const form = new FormData();
        form.append('file', file);
        return api('/resumes', { method: 'POST', form, idempotencyKey: key, timeoutMs: 15000 });
      }
      return api('/resumes', { method: 'POST', body: { text }, idempotencyKey: key });
    },
    onSuccess: () => {
      setText(''); setFile(null); setKey(newIdempotencyKey());
      qc.invalidateQueries({ queryKey: ['resumes'] });
    },
  });

  const activate = useMutation({
    mutationFn: (id) => api(`/resumes/${id}/activate`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['resumes'] }),
  });

  return (
    <div className="grid two">
      <section className="card">
        <h2>Add a resume</h2>
        <ErrorBanner error={upload.error} />
        <label>Upload a PDF (max 2 MB)
          <input id="resume-file" type="file" accept="application/pdf" onChange={(e) => { setFile(e.target.files[0] ?? null); setKey(newIdempotencyKey()); }} />
        </label>
        <p className="muted">or paste the text</p>
        <textarea id="resume-text" rows={10} maxLength={20000} value={text} disabled={!!file}
                  onChange={(e) => { setText(e.target.value); setKey(newIdempotencyKey()); }} />
        <button disabled={upload.isPending || (!file && !text.trim())} onClick={() => upload.mutate()}>
          {upload.isPending ? 'Saving…' : 'Save as active resume'}
        </button>
      </section>
      <section className="card">
        <h2>Your resumes</h2>
        <ErrorBanner error={resumes.error || activate.error} />
        {resumes.data?.length === 0 && <p className="muted">No resume yet. Add one to start matching.</p>}
        <ul className="list">
          {resumes.data?.map((r) => (
            <li key={r.id} className={r.active ? 'active' : ''}>
              <div className="small muted">{r.source} · {new Date(r.createdAt).toLocaleString()} · {r.characters} characters</div>
              <div className="preview">{r.preview}</div>
              {r.active ? <span className="pill">Active</span>
                : <button className="secondary" onClick={() => activate.mutate(r.id)}>Use this one</button>}
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
```

`src/pages/MatchPage.jsx`:

```jsx
import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api, newIdempotencyKey } from '../api/client.js';
import ErrorBanner from '../components/ErrorBanner.jsx';

export default function MatchPage() {
  const navigate = useNavigate();
  const [jd, setJd] = useState('');
  const [key, setKey] = useState(newIdempotencyKey);
  const [save, setSave] = useState({ company: '', role: '', jobUrl: '' });
  const [saveKey] = useState(newIdempotencyKey);

  const run = useMutation({
    mutationFn: () => api('/matches', { method: 'POST', body: { jobDescription: jd }, idempotencyKey: key, timeoutMs: 20000 })
      .then((r) => r.data),
    onSuccess: (m) => setSave({ company: m.company ?? '', role: m.role ?? '', jobUrl: '' }),
  });

  const saveApp = useMutation({
    mutationFn: () => api('/applications', {
      method: 'POST', idempotencyKey: saveKey,
      body: { company: save.company, role: save.role, jobUrl: save.jobUrl || null,
              source: { type: 'MATCH', id: String(run.data.id) } },
    }),
    onSuccess: () => navigate('/board'),
  });

  const m = run.data;
  return (
    <div className="grid two">
      <section className="card">
        <h2>Check your fit for a job</h2>
        <ErrorBanner error={run.error} />
        <textarea id="job-description" rows={14} maxLength={20000} placeholder="Paste the full job description"
                  value={jd} onChange={(e) => { setJd(e.target.value); setKey(newIdempotencyKey()); }} />
        <button disabled={run.isPending || !jd.trim()} onClick={() => run.mutate()}>
          {run.isPending ? 'Matching… (up to 20 seconds)' : 'Run AI match'}
        </button>
      </section>
      {m && (
        <section className="card">
          <div className="score" aria-label="Fit score">{m.fitScore}<span>/100</span></div>
          <h3>Missing skills</h3>
          {m.missingSkills.length ? <ul>{m.missingSkills.map((s) => <li key={s}>{s}</li>)}</ul> : <p className="muted">None found.</p>}
          <h3>Bullets tailored to this job</h3>
          <ol>{m.suggestedBullets.map((b, i) => <li key={i}>{b}</li>)}</ol>
          <h3>Save as a Wishlist application</h3>
          <ErrorBanner error={saveApp.error} />
          <label>Company<input id="save-company" value={save.company} onChange={(e) => setSave({ ...save, company: e.target.value })} required /></label>
          <label>Role<input id="save-role" value={save.role} onChange={(e) => setSave({ ...save, role: e.target.value })} required /></label>
          <label>Job link (optional)<input id="save-url" value={save.jobUrl} onChange={(e) => setSave({ ...save, jobUrl: e.target.value })} /></label>
          <button disabled={saveApp.isPending || !save.company.trim() || !save.role.trim()} onClick={() => saveApp.mutate()}>
            Save to pipeline
          </button>
        </section>
      )}
    </div>
  );
}
```

`src/pages/BoardPage.jsx`:

```jsx
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, newIdempotencyKey } from '../api/client.js';
import ErrorBanner from '../components/ErrorBanner.jsx';

const STAGES = ['WISHLIST', 'APPLIED', 'INTERVIEW', 'OFFER', 'REJECTED'];
const LABEL = { WISHLIST: 'Wishlist', APPLIED: 'Applied', INTERVIEW: 'Interview', OFFER: 'Offer', REJECTED: 'Rejected' };

export default function BoardPage() {
  const qc = useQueryClient();
  const apps = useQuery({ queryKey: ['applications'], queryFn: () => api('/applications').then((r) => r.data) });
  const [notice, setNotice] = useState(null);
  const [open, setOpen] = useState(null);
  const [newApp, setNewApp] = useState({ company: '', role: '' });
  const [createKey, setCreateKey] = useState(newIdempotencyKey);

  const move = useMutation({
    mutationFn: ({ app, stage }) => api(`/applications/${app.id}`, { method: 'PATCH', body: { stage }, ifMatch: `"${app.version}"` }),
    onMutate: async ({ app, stage }) => {                       // optimistic: move the card immediately
      await qc.cancelQueries({ queryKey: ['applications'] });
      const before = qc.getQueryData(['applications']);
      qc.setQueryData(['applications'], (list) => list.map((a) => (a.id === app.id ? { ...a, stage } : a)));
      return { before };
    },
    onError: (err, _vars, ctx) => {
      qc.setQueryData(['applications'], ctx.before);
      setNotice(err.status === 412 ? 'This card changed in another tab. The board has been reloaded.' : err.message);
    },
    onSettled: () => qc.invalidateQueries({ queryKey: ['applications'] }),
  });

  const create = useMutation({
    mutationFn: () => api('/applications', { method: 'POST', body: newApp, idempotencyKey: createKey }),
    onSuccess: () => { setNewApp({ company: '', role: '' }); setCreateKey(newIdempotencyKey()); qc.invalidateQueries({ queryKey: ['applications'] }); },
  });

  return (
    <>
      <ErrorBanner error={apps.error || create.error} />
      {notice && <div className="banner warn" role="status" onClick={() => setNotice(null)}>{notice}</div>}
      <form className="inline" onSubmit={(e) => { e.preventDefault(); create.mutate(); }}>
        <input id="new-company" placeholder="Company" value={newApp.company} onChange={(e) => setNewApp({ ...newApp, company: e.target.value })} required />
        <input id="new-role" placeholder="Role" value={newApp.role} onChange={(e) => setNewApp({ ...newApp, role: e.target.value })} required />
        <button disabled={create.isPending}>Add to Wishlist</button>
      </form>
      <div className="board">
        {STAGES.map((stage) => (
          <div key={stage} className="column" data-stage={stage}
               onDragOver={(e) => e.preventDefault()}
               onDrop={(e) => {
                 const app = apps.data.find((a) => a.id === Number(e.dataTransfer.getData('text/plain')));
                 if (app && app.stage !== stage) move.mutate({ app, stage });
               }}>
            <h3>{LABEL[stage]} <span className="muted">{apps.data?.filter((a) => a.stage === stage).length ?? 0}</span></h3>
            {apps.data?.filter((a) => a.stage === stage).map((a) => (
              <div key={a.id} className="kanban-card" draggable data-id={a.id}
                   onDragStart={(e) => e.dataTransfer.setData('text/plain', String(a.id))}
                   onClick={() => setOpen(open === a.id ? null : a.id)}>
                <strong>{a.company}</strong>
                <div>{a.role}</div>
                {a.appliedDate && <div className="small muted">Applied {a.appliedDate}</div>}
                {open === a.id && <Interviews applicationId={a.id} />}
              </div>
            ))}
          </div>
        ))}
      </div>
    </>
  );
}

function Interviews({ applicationId }) {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['interviews', applicationId], queryFn: () => api(`/applications/${applicationId}/interviews`).then((r) => r.data) });
  const [when, setWhen] = useState('');
  const [round, setRound] = useState('');
  const [key, setKey] = useState(newIdempotencyKey);
  const add = useMutation({
    mutationFn: () => api(`/applications/${applicationId}/interviews`, {
      method: 'POST', idempotencyKey: key, body: { scheduledAt: new Date(when).toISOString(), round: round || null },
    }),
    onSuccess: () => { setWhen(''); setRound(''); setKey(newIdempotencyKey()); qc.invalidateQueries({ queryKey: ['interviews', applicationId] }); },
  });
  return (
    <div className="interviews" onClick={(e) => e.stopPropagation()}>
      <ErrorBanner error={list.error || add.error} />
      {list.data?.map((i) => <div key={i.id} className="small">{new Date(i.scheduledAt).toLocaleString()} · {i.round ?? 'Interview'}</div>)}
      <input type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} />
      <input placeholder="Round (HR, Technical…)" value={round} onChange={(e) => setRound(e.target.value)} />
      <button className="secondary" disabled={!when || add.isPending} onClick={() => add.mutate()}>Add interview</button>
    </div>
  );
}
```

`src/pages/DashboardPage.jsx`:

```jsx
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client.js';
import ErrorBanner from '../components/ErrorBanner.jsx';

const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;

export default function DashboardPage() {
  const d = useQuery({
    queryKey: ['dashboard', tz],
    queryFn: () => api(`/dashboard?tz=${encodeURIComponent(tz)}`).then((r) => r.data),
    refetchInterval: 15_000,
  });
  if (d.error) return <ErrorBanner error={d.error} />;
  if (!d.data) return <p className="muted">Loading…</p>;
  const { byStage, interviewsThisWeek, sent, replied, responseRate } = d.data;
  return (
    <div className="grid three">
      <section className="card">
        <h2>Applications by stage</h2>
        <table className="stages"><tbody>
          {Object.entries(byStage).map(([stage, n]) => <tr key={stage}><td>{stage.toLowerCase()}</td><td className="num">{n}</td></tr>)}
        </tbody></table>
      </section>
      <section className="card">
        <h2>Interviews this week</h2>
        <div className="big" id="interviews-this-week">{interviewsThisWeek}</div>
        <p className="muted small">Monday to Sunday, {tz}</p>
      </section>
      <section className="card">
        <h2>Response rate</h2>
        <div className="big" id="response-rate">{responseRate == null ? '—' : `${Math.round(responseRate * 100)}%`}</div>
        <p className="muted small">{replied} replies from {sent} sent</p>
      </section>
    </div>
  );
}
```

`src/pages/JobFeedPage.jsx`:

```jsx
import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, newIdempotencyKey } from '../api/client.js';
import ErrorBanner from '../components/ErrorBanner.jsx';

const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
const EMPTY = { keywords: '', country: 'in', location: '', radiusKm: 25, remoteOk: false, enabled: true, timezone: tz };

export default function JobFeedPage() {
  const qc = useQueryClient();
  const profile = useQuery({
    queryKey: ['search-profile'], retry: false,
    queryFn: () => api('/search-profile').then((r) => r.data).catch((e) => (e.status === 404 ? null : Promise.reject(e))),
  });
  const feed = useQuery({ queryKey: ['jobs'], queryFn: () => api('/jobs').then((r) => r.data), refetchInterval: 30_000 });
  const [form, setForm] = useState(EMPTY);
  useEffect(() => { if (profile.data) setForm({ ...profile.data, timezone: tz }); }, [profile.data]);

  const saveProfile = useMutation({
    mutationFn: () => api('/search-profile', { method: 'PUT', body: { ...form, radiusKm: Number(form.radiusKm) } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['search-profile'] }),
  });
  const refresh = useMutation({
    mutationFn: () => api('/jobs/refresh', { method: 'POST', idempotencyKey: newIdempotencyKey() }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['jobs'] }),
  });
  const save = useMutation({
    mutationFn: (id) => api(`/jobs/${id}/save`, { method: 'POST' }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['jobs'] }); qc.invalidateQueries({ queryKey: ['applications'] }); },
  });
  const dismiss = useMutation({
    mutationFn: (id) => api(`/jobs/${id}/dismiss`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['jobs'] }),
  });

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value });
  return (
    <div className="grid feed">
      <section className="card">
        <h2>Your job search</h2>
        <ErrorBanner error={profile.error || saveProfile.error} />
        <label>Keywords<input id="feed-keywords" value={form.keywords} onChange={set('keywords')} maxLength={100} /></label>
        <label>Country code<input id="feed-country" value={form.country} onChange={set('country')} maxLength={2} /></label>
        <label>City or area<input id="feed-location" value={form.location} onChange={set('location')} maxLength={100} /></label>
        <label>Radius (km)<input id="feed-radius" type="number" min={5} max={100} value={form.radiusKm} onChange={set('radiusKm')} /></label>
        <label className="check"><input type="checkbox" checked={form.remoteOk} onChange={set('remoteOk')} /> Include remote jobs</label>
        <label className="check"><input type="checkbox" checked={form.enabled} onChange={set('enabled')} /> Search every morning</label>
        <button disabled={saveProfile.isPending} onClick={() => saveProfile.mutate()}>Save search</button>
      </section>
      <section>
        <div className="inline">
          <button disabled={refresh.isPending || !profile.data} onClick={() => refresh.mutate()}>Refresh now</button>
          <span className="muted small">
            {feed.data?.lastRunAt ? `Last run ${new Date(feed.data.lastRunAt).toLocaleString()}` : 'No run yet'}
            {feed.data?.latestRunStatus && feed.data.latestRunStatus !== 'DONE' && ` · ${feed.data.latestRunStatus.toLowerCase().replaceAll('_', ' ')}`}
          </span>
        </div>
        <ErrorBanner error={feed.error || refresh.error || save.error || dismiss.error} />
        {feed.data?.lastError && <div className="banner warn">{feed.data.lastError}</div>}
        {feed.data?.items.length === 0 && <p className="muted">No new postings yet. Your feed fills in each morning, or press Refresh now.</p>}
        {feed.data?.items.map((j) => (
          <article key={j.postingId} className="card job">
            <div className="quick" title="Quick score from the posting snippet">{j.quickScore ?? '–'}<span>Quick score</span></div>
            <div>
              <a href={j.url} target="_blank" rel="noopener noreferrer"><strong>{j.title}</strong></a>
              <div className="muted small">{j.company} · {j.location}{j.salaryMin ? ` · from ${j.salaryMin.toLocaleString()}` : ''}</div>
              {j.reason && <p>{j.reason}</p>}
              {j.missingSkills?.length > 0 && <p className="small">Missing: {j.missingSkills.join(', ')}</p>}
              <div className="inline">
                <button onClick={() => save.mutate(j.postingId)}>Save to pipeline</button>
                <button className="secondary" onClick={() => dismiss.mutate(j.postingId)}>Dismiss</button>
              </div>
            </div>
          </article>
        ))}
        <p className="attribution small"><a href="https://www.adzuna.com" target="_blank" rel="noopener noreferrer">Jobs by Adzuna</a></p>
      </section>
    </div>
  );
}
```

## Step 9.6 — Styles

`src/index.css`:

```css
:root {
  --bg: #f5f6f8; --surface: #fff; --fg: #18202c; --muted: #5a6577; --line: #d7dce4;
  --accent: #2f55c4; --accent-soft: #e5ebfa; --warn: #8c5a12; --warn-soft: #fbf1e2; --error: #b42318; --error-soft: #fdecea;
  font-family: Inter, system-ui, sans-serif; color: var(--fg); background: var(--bg);
}
@media (prefers-color-scheme: dark) {
  :root { --bg: #0f141b; --surface: #161d27; --fg: #e3e8f0; --muted: #98a3b4; --line: #283242;
          --accent: #84a3ff; --accent-soft: #1d2a48; --warn: #e2ae5c; --warn-soft: #2a2216; --error: #f07a6e; --error-soft: #2c1715; }
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); }
.topbar { display: flex; justify-content: space-between; align-items: center; gap: 16px; flex-wrap: wrap;
          padding: 12px max(16px, 3vw); background: var(--surface); border-bottom: 1px solid var(--line); }
.topbar nav { display: flex; gap: 14px; flex-wrap: wrap; align-items: center; }
.topbar a { color: var(--muted); text-decoration: none; }
.topbar a.active { color: var(--accent); font-weight: 600; }
.content { padding: 24px max(16px, 3vw); max-width: 1300px; margin: 0 auto; }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: 10px; padding: 18px; display: grid; gap: 10px; align-content: start; }
.narrow { max-width: 420px; margin: 40px auto; }
.grid { display: grid; gap: 16px; }
.grid.two { grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); }
.grid.three { grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); }
.grid.feed { grid-template-columns: minmax(260px, 320px) 1fr; }
@media (max-width: 800px) { .grid.feed { grid-template-columns: 1fr; } }
label { display: grid; gap: 4px; font-size: 14px; }
label.check { display: flex; gap: 8px; align-items: center; }
input, textarea { font: inherit; padding: 8px 10px; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: var(--fg); }
button { font: inherit; padding: 8px 14px; border-radius: 6px; border: 0; background: var(--accent); color: #fff; cursor: pointer; }
button.secondary { background: var(--accent-soft); color: var(--accent); }
button.link { background: none; color: var(--muted); padding: 0; }
button:disabled { opacity: .55; cursor: default; }
button:focus-visible, a:focus-visible, input:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.banner { padding: 10px 12px; border-radius: 6px; }
.banner.error { background: var(--error-soft); color: var(--error); }
.banner.warn { background: var(--warn-soft); color: var(--warn); cursor: pointer; }
.muted { color: var(--muted); } .small { font-size: 13px; } .pad { padding: 24px; }
.inline { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-bottom: 12px; }
.list { list-style: none; padding: 0; display: grid; gap: 10px; }
.list li { border: 1px solid var(--line); border-radius: 8px; padding: 10px; display: grid; gap: 6px; }
.list li.active { border-color: var(--accent); }
.preview { white-space: pre-wrap; font-size: 14px; }
.pill { justify-self: start; background: var(--accent-soft); color: var(--accent); border-radius: 99px; padding: 2px 10px; font-size: 12px; }
.score { font-size: 48px; font-weight: 700; color: var(--accent); } .score span { font-size: 18px; color: var(--muted); }
.big { font-size: 40px; font-weight: 700; }
.stages td { padding: 4px 8px; } .num { text-align: right; font-variant-numeric: tabular-nums; }
.board { display: grid; grid-template-columns: repeat(5, minmax(200px, 1fr)); gap: 12px; overflow-x: auto; }
.column { background: var(--surface); border: 1px solid var(--line); border-radius: 10px; padding: 10px; min-height: 300px; display: grid; gap: 8px; align-content: start; }
.kanban-card { background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 10px; cursor: grab; display: grid; gap: 4px; }
.interviews { display: grid; gap: 6px; margin-top: 8px; cursor: default; }
.job { grid-template-columns: 80px 1fr; margin-bottom: 12px; }
.quick { font-size: 28px; font-weight: 700; color: var(--accent); display: grid; }
.quick span { font-size: 11px; font-weight: 500; color: var(--muted); text-transform: uppercase; letter-spacing: .05em; }
.attribution { text-align: right; }
```

## Step 9.7 — Run the full stack and click through

Start Docker Compose infrastructure and every service (`tools/run-service.sh <name>` in eight terminals, or use Phase 12's single command once it's written). Then:

```bash
cd frontend && npm run dev
```

**Verify each page at `http://localhost:5173`:**

1. **Sign up** → you land on Dashboard showing zeros and "—".
2. **Resume:** paste text → it appears as Active.
3. **Match:** paste a job description → a score appears in 5–15 s → **Save to pipeline** → you land on the Pipeline page with a Wishlist card.
4. **Pipeline:** drag the card to Applied → it moves at once. Open the same page in a second tab, drag the card there, then drag it in the first tab → the banner "This card changed in another tab…" appears.
5. **Dashboard:** within about 15 s shows Applied = 1.
6. **Job Feed:** save a search → **Refresh now** → postings appear after the batch completes (minutes) → **Save to pipeline** adds a Wishlist card.
7. **Reload the browser** → you stay logged in (silent refresh). **Log out** → you go back to Login, and reloading keeps you logged out.
8. In DevTools → Application → Local Storage: **nothing** is stored there. Cookies: `hiretrack_refresh` is `HttpOnly`.

## Phase demo

```bash
cd frontend && npm run build && ls -la dist/index.html dist/assets | head
```

Expected: a production build in `dist/` with no errors. Phase 12 serves this build from nginx at `http://localhost`.

## Done when

- [ ] All seven pages work against the real back end
- [ ] Double-clicking any create button creates one record (check the network tab: the same `Idempotency-Key`)
- [ ] A conflicting edit from another tab shows the 412 message and reloads the board
- [ ] The access token is never in localStorage; reloads restore the session through the cookie
- [ ] `npm run build` succeeds
- [ ] Commit: `git commit -am "Phase 9: frontend"`
