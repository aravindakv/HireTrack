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