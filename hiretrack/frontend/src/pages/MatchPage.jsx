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