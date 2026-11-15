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