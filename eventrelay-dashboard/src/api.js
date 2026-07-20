const KEY_STORAGE = 'eventrelay.apiKey';

export const getKey = () => localStorage.getItem(KEY_STORAGE) || '';
export const setKey = (k) => localStorage.setItem(KEY_STORAGE, k);
export const clearKey = () => localStorage.removeItem(KEY_STORAGE);

async function request(path, options = {}) {
  const res = await fetch(`/api/v1${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${getKey()}`,
      ...(options.headers || {}),
    },
  });

  if (res.status === 401) throw new Error('Unauthorized — check your API key.');
  if (!res.ok) {
    let detail = `Request failed (${res.status})`;
    try {
      const body = await res.json();
      if (body.message) detail = body.message;
    } catch { /* non-JSON error body */ }
    throw new Error(detail);
  }
  return res.status === 204 ? null : res.json();
}

export const api = {
  stats: () => request('/stats'),
  events: (page = 0, size = 20) => request(`/events?page=${page}&size=${size}`),
  deliveries: (eventId) => request(`/events/${eventId}/deliveries`),
  replay: (eventId) => request(`/events/${eventId}/replay`, { method: 'POST' }),
  deadLetter: (page = 0, size = 20) => request(`/dead-letter?page=${page}&size=${size}`),
  subscriptions: () => request('/subscriptions'),
  createSubscription: (body) =>
    request('/subscriptions', { method: 'POST', body: JSON.stringify(body) }),
};
