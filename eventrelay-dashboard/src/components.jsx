import { IconBolt, IconCheck, IconDead, IconEvents, IconLink, IconRetry } from './icons.jsx';

export function Field() {
  return (
    <div className="field" aria-hidden="true">
      <div className="blob blob-1" />
      <div className="blob blob-2" />
      <div className="blob blob-3" />
      <div className="blob blob-4" />
    </div>
  );
}

export function Card({ title, sub, action, children, className = '' }) {
  return (
    <section className={`card fade-in ${className}`}>
      {(title || action) && (
        <div className="card-head">
          <div>
            {title && <h2 className="card-title">{title}</h2>}
            {sub && <p className="card-sub">{sub}</p>}
          </div>
          {action && <div style={{ marginLeft: 'auto' }}>{action}</div>}
        </div>
      )}
      {children}
    </section>
  );
}

const TILE_ICONS = {
  events: [IconEvents, 'violet'],
  delivered: [IconCheck, 'green'],
  retrying: [IconRetry, 'amber'],
  dead: [IconDead, 'rose'],
  rate: [IconBolt, 'green'],
  subs: [IconLink, 'violet'],
};

export function Tile({ kind, value, label }) {
  const [Icon, tone] = TILE_ICONS[kind] || TILE_ICONS.events;
  return (
    <div className="tile">
      <div className={`tile-icon ${tone}`}><Icon /></div>
      <div>
        <div className="tile-value">{value}</div>
        <div className="tile-label">{label}</div>
      </div>
    </div>
  );
}

const STATUS_TONE = {
  DELIVERED: 'pill-green',
  SUCCESS: 'pill-green',
  RETRYING: 'pill-amber',
  QUEUED: 'pill-violet',
  PENDING: 'pill-violet',
  DEAD: 'pill-rose',
  FAILED: 'pill-rose',
  TIMEOUT: 'pill-rose',
  SKIPPED: 'pill-grey',
  REPLAYED: 'pill-violet',
  ACTIVE: 'pill-green',
};

export function Pill({ status }) {
  return <span className={`pill ${STATUS_TONE[status] || 'pill-grey'}`}>{status}</span>;
}

const METER_COLORS = {
  DELIVERED: '#10b981',
  QUEUED: '#818cf8',
  PENDING: '#a5b4fc',
  RETRYING: '#fbbf24',
  DEAD: '#fb7185',
};

export function StatusMeter({ byStatus, total }) {
  const entries = Object.entries(byStatus || {}).filter(([, v]) => v > 0);
  if (!total || entries.length === 0) {
    return <div className="empty">No deliveries yet — ingest an event to see it flow through.</div>;
  }
  return (
    <div>
      <div className="meter">
        {entries.map(([status, count]) => (
          <span
            key={status}
            style={{ width: `${(count / total) * 100}%`, background: METER_COLORS[status] || '#cbd5e1' }}
            title={`${status}: ${count}`}
          />
        ))}
      </div>
      <div className="meter-legend">
        {entries.map(([status, count]) => (
          <span className="legend-item" key={status}>
            <span className="legend-dot" style={{ background: METER_COLORS[status] || '#cbd5e1' }} />
            {status} · {count.toLocaleString()}
          </span>
        ))}
      </div>
    </div>
  );
}

export function Spinner() {
  return <span className="spinner" role="status" aria-label="Loading" />;
}

export function Empty({ children }) {
  return <div className="empty">{children}</div>;
}

export const shortId = (id) => (id ? `${String(id).slice(0, 8)}…` : '—');

export const when = (ts) => {
  if (!ts) return '—';
  const d = new Date(ts);
  const diff = (Date.now() - d.getTime()) / 1000;
  if (diff < 60) return `${Math.max(0, Math.floor(diff))}s ago`;
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
  return d.toLocaleDateString();
};
