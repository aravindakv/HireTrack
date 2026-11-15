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