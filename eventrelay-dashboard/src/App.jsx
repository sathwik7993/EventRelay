import { useCallback, useEffect, useState } from 'react';
import { api, clearKey, getKey, setKey } from './api.js';
import { Card, Empty, Field, Pill, Spinner, StatusMeter, Tile, shortId, when } from './components.jsx';
import {
  IconBack, IconDead, IconEvents, IconKey, IconLink, IconLogout,
  IconPulse, IconRefresh, IconRetry, IconSend,
} from './icons.jsx';

/* ------------------------------------------------------------------ */
/* Key gate                                                            */
/* ------------------------------------------------------------------ */
function KeyGate({ onSaved }) {
  const [value, setValue] = useState('');
  return (
    <div className="gate">
      <Field />
      <div className="gate-card glass fade-in">
        <div className="brand-mark" style={{ width: 52, height: 52, borderRadius: 18 }}>
          <IconPulse width={26} height={26} />
        </div>
        <h2>EventRelay Console</h2>
        <p>
          Paste the tenant API key returned when the tenant was created. It is stored
          only in this browser’s local storage and sent as a bearer token.
        </p>
        <form
          onSubmit={(e) => { e.preventDefault(); if (value.trim()) { setKey(value.trim()); onSaved(); } }}
          className="stack"
        >
          <div>
            <label className="field-label" htmlFor="key">API key</label>
            <input
              id="key" className="input" autoFocus placeholder="er_live_…"
              value={value} onChange={(e) => setValue(e.target.value)}
            />
          </div>
          <button className="btn btn-primary" type="submit" disabled={!value.trim()}>
            <IconKey /> Open console
          </button>
        </form>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Overview                                                            */
/* ------------------------------------------------------------------ */
function Overview({ stats }) {
  if (!stats) return <Card><Spinner /></Card>;
  return (
    <>
      <div className="tiles fade-in">
        <Tile kind="events" value={stats.events.toLocaleString()} label="Events ingested" />
        <Tile kind="delivered" value={(stats.deliveriesByStatus?.DELIVERED || 0).toLocaleString()} label="Delivered" />
        <Tile kind="retrying" value={(stats.deliveriesByStatus?.RETRYING || 0).toLocaleString()} label="Retrying" />
        <Tile kind="dead" value={stats.deadLettered.toLocaleString()} label="Dead-lettered" />
        <Tile kind="rate" value={`${stats.successRate}%`} label="Delivery success rate" />
        <Tile kind="subs" value={stats.subscriptions.toLocaleString()} label="Active subscriptions" />
      </div>

      <Card title="Delivery pipeline" sub="Every delivery, by state">
        <StatusMeter byStatus={stats.deliveriesByStatus} total={stats.deliveriesTotal} />
      </Card>
    </>
  );
}

/* ------------------------------------------------------------------ */
/* Events + drill-in                                                   */
/* ------------------------------------------------------------------ */
function Events({ notify }) {
  const [page, setPage] = useState(null);
  const [selected, setSelected] = useState(null);
  const [attempts, setAttempts] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try { setPage(await api.events(0, 25)); } catch (e) { notify(e.message, true); }
  }, [notify]);

  useEffect(() => { load(); }, [load]);

  const open = async (event) => {
    setSelected(event); setAttempts(null);
    try { setAttempts(await api.deliveries(event.id)); } catch (e) { notify(e.message, true); }
  };

  const replay = async () => {
    setBusy(true);
    try {
      const r = await api.replay(selected.id);
      notify(`Re-queued ${r.replayed} dead-lettered ${r.replayed === 1 ? 'delivery' : 'deliveries'}.`);
      setAttempts(await api.deliveries(selected.id));
    } catch (e) { notify(e.message, true); } finally { setBusy(false); }
  };

  if (selected) {
    return (
      <Card
        title={selected.eventType}
        sub={selected.id}
        action={
          <div className="row">
            <button className="btn btn-sm" onClick={replay} disabled={busy}>
              <IconRetry /> Replay
            </button>
            <button className="btn btn-sm" onClick={() => setSelected(null)}>
              <IconBack /> Back
            </button>
          </div>
        }
      >
        {!attempts ? <Spinner /> : attempts.length === 0 ? (
          <Empty>No delivery attempts recorded yet.</Empty>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>#</th><th>Status</th><th>HTTP</th><th>Duration</th><th>Target</th><th>When</th><th>Error</th></tr>
              </thead>
              <tbody>
                {attempts.map((a) => (
                  <tr key={a.id}>
                    <td><strong>{a.attemptNumber}</strong></td>
                    <td><Pill status={a.status} /></td>
                    <td className="mono">{a.httpStatusCode ?? '—'}</td>
                    <td className="mono">{a.durationMs}ms</td>
                    <td className="mono">{a.targetUrl}</td>
                    <td className="mono">{when(a.createdAt)}</td>
                    <td className="mono" style={{ maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {a.errorMessage || '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    );
  }

  return (
    <Card
      title="Event log"
      sub={page ? `${page.totalElements.toLocaleString()} events` : 'Loading…'}
      action={<button className="btn btn-sm" onClick={load}><IconRefresh /> Refresh</button>}
    >
      {!page ? <Spinner /> : page.data.length === 0 ? (
        <Empty>No events yet. POST to <span className="mono">/api/v1/events</span> to get started.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr><th>Event</th><th>Type</th><th>Idempotency key</th><th>Ingested</th></tr>
            </thead>
            <tbody>
              {page.data.map((e) => (
                <tr key={e.id} className="clickable" onClick={() => open(e)}>
                  <td className="mono">{shortId(e.id)}</td>
                  <td><strong>{e.eventType}</strong></td>
                  <td className="mono">{e.idempotencyKey || '—'}</td>
                  <td className="mono">{when(e.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

/* ------------------------------------------------------------------ */
/* Dead letter queue                                                   */
/* ------------------------------------------------------------------ */
function DeadLetter({ notify }) {
  const [page, setPage] = useState(null);
  const [busy, setBusy] = useState(null);

  const load = useCallback(async () => {
    try { setPage(await api.deadLetter(0, 25)); } catch (e) { notify(e.message, true); }
  }, [notify]);

  useEffect(() => { load(); }, [load]);

  const replay = async (eventId) => {
    setBusy(eventId);
    try {
      const r = await api.replay(eventId);
      notify(`Re-queued ${r.replayed} ${r.replayed === 1 ? 'delivery' : 'deliveries'}.`);
      await load();
    } catch (e) { notify(e.message, true); } finally { setBusy(null); }
  };

  return (
    <Card
      title="Dead-letter queue"
      sub={page ? `${page.totalElements.toLocaleString()} entries` : 'Loading…'}
      action={<button className="btn btn-sm" onClick={load}><IconRefresh /> Refresh</button>}
    >
      {!page ? <Spinner /> : page.data.length === 0 ? (
        <Empty>Nothing dead-lettered. Every event reached its destination.</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr><th>Event</th><th>Type</th><th>Status</th><th>Attempts</th><th>HTTP</th><th>Reason</th><th>Failed</th><th /></tr>
            </thead>
            <tbody>
              {page.data.map((d) => (
                <tr key={d.id}>
                  <td className="mono">{shortId(d.eventId)}</td>
                  <td><strong>{d.eventType}</strong></td>
                  <td><Pill status={d.status} /></td>
                  <td className="mono">{d.totalAttempts}</td>
                  <td className="mono">{d.lastHttpStatus ?? '—'}</td>
                  <td className="mono" style={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {d.failureReason}
                  </td>
                  <td className="mono">{when(d.failedAt)}</td>
                  <td>
                    <button
                      className="btn btn-sm"
                      disabled={busy === d.eventId || d.status !== 'PENDING'}
                      onClick={() => replay(d.eventId)}
                    >
                      <IconRetry /> Replay
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

/* ------------------------------------------------------------------ */
/* Subscriptions                                                       */
/* ------------------------------------------------------------------ */
function Subscriptions({ notify }) {
  const [list, setList] = useState(null);
  const [url, setUrl] = useState('');
  const [types, setTypes] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try { setList(await api.subscriptions()); } catch (e) { notify(e.message, true); }
  }, [notify]);

  useEffect(() => { load(); }, [load]);

  const create = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      await api.createSubscription({
        targetUrl: url.trim(),
        eventTypes: types.split(',').map((t) => t.trim()).filter(Boolean),
      });
      notify('Subscription created.');
      setUrl(''); setTypes('');
      await load();
    } catch (e) { notify(e.message, true); } finally { setBusy(false); }
  };

  return (
    <>
      <Card title="New subscription" sub="Target URLs are checked against the SSRF policy">
        <form onSubmit={create} className="stack">
          <div className="grid-2">
            <div>
              <label className="field-label" htmlFor="url">Target URL</label>
              <input id="url" className="input" placeholder="https://example.com/webhooks"
                value={url} onChange={(e) => setUrl(e.target.value)} />
            </div>
            <div>
              <label className="field-label" htmlFor="types">Event types (comma separated)</label>
              <input id="types" className="input" placeholder="order.created, payment.*"
                value={types} onChange={(e) => setTypes(e.target.value)} />
            </div>
          </div>
          <div>
            <button className="btn btn-primary" disabled={busy || !url.trim()}>
              <IconSend /> Create subscription
            </button>
          </div>
        </form>
      </Card>

      <Card
        title="Subscriptions"
        sub={list ? `${list.length} configured` : 'Loading…'}
        action={<button className="btn btn-sm" onClick={load}><IconRefresh /> Refresh</button>}
      >
        {!list ? <Spinner /> : list.length === 0 ? (
          <Empty>No subscriptions yet — create one above.</Empty>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Target</th><th>Event types</th><th>Status</th><th>Signing secret</th><th>Created</th></tr>
              </thead>
              <tbody>
                {list.map((s) => (
                  <tr key={s.id}>
                    <td className="mono">{s.targetUrl}</td>
                    <td>{s.eventTypes?.length ? s.eventTypes.join(', ') : <em>all</em>}</td>
                    <td><Pill status={s.status} /></td>
                    <td className="mono">{s.signingSecret?.slice(0, 14)}…</td>
                    <td className="mono">{when(s.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}

/* ------------------------------------------------------------------ */
/* Shell                                                               */
/* ------------------------------------------------------------------ */
const NAV = [
  { id: 'overview', label: 'Overview', Icon: IconPulse },
  { id: 'events', label: 'Events', Icon: IconEvents },
  { id: 'dlq', label: 'Dead letters', Icon: IconDead },
  { id: 'subs', label: 'Subscriptions', Icon: IconLink },
];

const SUBTITLE = {
  overview: 'Live delivery health for your tenant',
  events: 'Every event accepted, and how it was delivered',
  dlq: 'Deliveries that exhausted retries — inspect and replay',
  subs: 'Where matching events get delivered',
};

export default function App() {
  const [authed, setAuthed] = useState(Boolean(getKey()));
  const [view, setView] = useState('overview');
  const [stats, setStats] = useState(null);
  const [toast, setToast] = useState(null);

  const notify = useCallback((message, isError = false) => {
    setToast({ message, isError });
    setTimeout(() => setToast(null), 4200);
  }, []);

  const refreshStats = useCallback(async () => {
    try { setStats(await api.stats()); }
    catch (e) {
      if (e.message.includes('Unauthorized')) { clearKey(); setAuthed(false); }
      else notify(e.message, true);
    }
  }, [notify]);

  useEffect(() => {
    if (!authed) return;
    refreshStats();
    const t = setInterval(refreshStats, 5000); // live tiles
    return () => clearInterval(t);
  }, [authed, refreshStats]);

  if (!authed) return <KeyGate onSaved={() => setAuthed(true)} />;

  return (
    <>
      <Field />
      <div className="shell">
        <aside className="rail glass">
          <div className="brand">
            <div className="brand-mark"><IconPulse width={22} height={22} /></div>
            <div>
              <div className="brand-name">EventRelay</div>
              <div className="brand-sub">Delivery console</div>
            </div>
          </div>

          <nav className="nav">
            {NAV.map(({ id, label, Icon }) => (
              <button
                key={id}
                className={`nav-item ${view === id ? 'active' : ''}`}
                onClick={() => setView(id)}
              >
                <Icon /> {label}
              </button>
            ))}
          </nav>

          <div className="rail-foot">
            <button className="nav-item" onClick={() => { clearKey(); setAuthed(false); }}>
              <IconLogout /> Sign out
            </button>
          </div>
        </aside>

        <main className="main">
          <header className="topbar glass">
            <div>
              <h1>{NAV.find((n) => n.id === view)?.label}</h1>
              <p>{SUBTITLE[view]}</p>
            </div>
            <div className="topbar-spacer" />
            {stats && <span className="chip"><IconPulse width={14} height={14} /> {stats.successRate}% success</span>}
            <button className="btn btn-sm" onClick={refreshStats}><IconRefresh /> Refresh</button>
          </header>

          <div className="content">
            {toast && (
              <div className={`banner ${toast.isError ? '' : 'banner-ok'} fade-in`}>{toast.message}</div>
            )}
            {view === 'overview' && <Overview stats={stats} />}
            {view === 'events' && <Events notify={notify} />}
            {view === 'dlq' && <DeadLetter notify={notify} />}
            {view === 'subs' && <Subscriptions notify={notify} />}
          </div>
        </main>
      </div>
    </>
  );
}
