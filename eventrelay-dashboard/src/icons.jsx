// Minimal inline icon set (stroke-based, 1.75px) — keeps the bundle dependency-free.
const base = {
  width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none',
  stroke: 'currentColor', strokeWidth: 1.75, strokeLinecap: 'round', strokeLinejoin: 'round',
};

export const IconPulse = (p) => (
  <svg {...base} {...p}><path d="M3 12h4l3 8 4-16 3 8h4" /></svg>
);
export const IconEvents = (p) => (
  <svg {...base} {...p}><path d="M4 6h16M4 12h16M4 18h10" /></svg>
);
export const IconDead = (p) => (
  <svg {...base} {...p}><path d="M12 9v4m0 4h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z" /></svg>
);
export const IconLink = (p) => (
  <svg {...base} {...p}><path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7" /><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7" /></svg>
);
export const IconCheck = (p) => (
  <svg {...base} {...p}><path d="M20 6 9 17l-5-5" /></svg>
);
export const IconRetry = (p) => (
  <svg {...base} {...p}><path d="M3 12a9 9 0 1 0 3-6.7L3 8" /><path d="M3 3v5h5" /></svg>
);
export const IconSend = (p) => (
  <svg {...base} {...p}><path d="m22 2-7 20-4-9-9-4Z" /><path d="M22 2 11 13" /></svg>
);
export const IconKey = (p) => (
  <svg {...base} {...p}><circle cx="7.5" cy="15.5" r="4.5" /><path d="m10.7 12.3 8.8-8.8M17 7l2.5 2.5M14.5 9.5 17 12" /></svg>
);
export const IconRefresh = (p) => (
  <svg {...base} {...p}><path d="M21 12a9 9 0 1 1-3-6.7L21 8" /><path d="M21 3v5h-5" /></svg>
);
export const IconBack = (p) => (
  <svg {...base} {...p}><path d="M19 12H5M12 19l-7-7 7-7" /></svg>
);
export const IconLogout = (p) => (
  <svg {...base} {...p}><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" /></svg>
);
export const IconBolt = (p) => (
  <svg {...base} {...p}><path d="M13 2 3 14h9l-1 8 10-12h-9Z" /></svg>
);
