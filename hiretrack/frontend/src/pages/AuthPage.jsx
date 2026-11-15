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