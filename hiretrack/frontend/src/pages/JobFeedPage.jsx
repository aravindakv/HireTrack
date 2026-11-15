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