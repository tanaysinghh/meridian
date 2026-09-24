import { Client } from '@stomp/stompjs';
import { API_BASE } from './api.js';

// Realtime channel to the API.
//
// The backend moved from Socket.IO to STOMP over WebSocket when it became a Spring
// Boot service. This module keeps the small emitter API the rest of the app already
// uses — getSocket().on('pr.scored', fn) / .off(...) — so AppShell and Overview did
// not have to change.
//
// The mapping from the old model:
//   - Socket.IO rooms  -> a STOMP destination per org, /topic/org.{orgId}
//   - Socket.IO events -> an `event` field inside the message body, since a STOMP
//                         frame carries a destination but no event name
//   - handshake auth   -> the httpOnly `mrd_at` cookie, sent with the upgrade request
//
// Connection lifecycle is surfaced through the same three pseudo-events the old client
// emitted ('connect', 'disconnect', 'connect_error') so the header's live indicator
// keeps working unchanged.

const ENDPOINT_PATH = '/live';

let client = null;
let socket = null;

/** Where to open the WebSocket. */
function endpointUrl() {
  // In prod the API is a separate origin (VITE_API_BASE). In dev this stays relative so
  // Vite's proxy forwards /live to the API with ws:true — see vite.config.js.
  const base = (import.meta.env?.VITE_API_BASE || '').replace(/\/$/, '');
  if (base) {
    return base.replace(/^http/, 'ws') + ENDPOINT_PATH;
  }
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${window.location.host}${ENDPOINT_PATH}`;
}

/**
 * Reads the current user's org id out of the access-token payload so we know which
 * destination to subscribe to.
 *
 * The JWT is in an httpOnly cookie and deliberately unreadable here, so this falls back
 * to /me. Only the `org` claim is needed; nothing is trusted from it beyond choosing a
 * destination, and the server independently refuses a subscription to any org but the
 * caller's own.
 */
async function resolveOrgId() {
  const res = await fetch(`${API_BASE}/me`, { credentials: 'include' });
  if (!res.ok) throw new Error('not_authenticated');
  const body = await res.json();
  return body?.user?.org_id;
}

/** Minimal event emitter with the subset of the Socket.IO surface this app used. */
function createEmitter() {
  const handlers = new Map();

  return {
    on(event, handler) {
      if (!handlers.has(event)) handlers.set(event, new Set());
      handlers.get(event).add(handler);
      return this;
    },
    off(event, handler) {
      if (!handlers.has(event)) return this;
      if (handler) handlers.get(event).delete(handler);
      // Socket.IO's off(event) with no handler removes them all.
      else handlers.get(event).clear();
      return this;
    },
    emit(event, payload) {
      const set = handlers.get(event);
      if (!set) return;
      for (const handler of [...set]) {
        try {
          handler(payload);
        } catch (err) {
          console.error(`[realtime] handler for "${event}" threw`, err);
        }
      }
    },
  };
}

/**
 * Returns the shared realtime connection, opening it on first use.
 *
 * Safe to call before the user is authenticated: the connection attempt fails, reports
 * 'connect_error', and stompjs retries — so it recovers on its own once a session exists.
 */
export function getSocket() {
  if (socket) return socket;

  socket = createEmitter();

  client = new Client({
    brokerURL: endpointUrl(),
    // The browser attaches the httpOnly mrd_at cookie to the upgrade request; the server
    // reads it during the handshake. No token is passed through JS.
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: async () => {
      socket.emit('connect');
      try {
        const orgId = await resolveOrgId();
        if (!orgId) return;
        client.subscribe(`/topic/org.${orgId}`, (message) => {
          let body;
          try {
            body = JSON.parse(message.body);
          } catch {
            return;
          }
          // Unwrap the envelope back into the event/payload shape the app expects.
          if (body?.event) socket.emit(body.event, body.data ?? {});
        });
      } catch {
        // Not signed in yet; the next reconnect will pick it up.
      }
    },
    onDisconnect: () => socket.emit('disconnect'),
    onWebSocketClose: () => socket.emit('disconnect'),
    onStompError: () => socket.emit('connect_error'),
    onWebSocketError: () => socket.emit('connect_error'),
  });

  client.activate();
  return socket;
}

/** Closes the connection and resets module state. Used on sign-out. */
export function closeSocket() {
  if (client) {
    client.deactivate();
    client = null;
  }
  socket = null;
}
